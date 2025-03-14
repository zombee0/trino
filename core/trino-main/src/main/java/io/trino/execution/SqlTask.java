/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.trino.execution;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import io.airlift.concurrent.SetThreadName;
import io.airlift.log.Logger;
import io.airlift.stats.CounterStat;
import io.airlift.units.DataSize;
import io.airlift.units.Duration;
import io.trino.Session;
import io.trino.exchange.ExchangeManagerRegistry;
import io.trino.execution.DynamicFiltersCollector.VersionedDynamicFilterDomains;
import io.trino.execution.StateMachine.StateChangeListener;
import io.trino.execution.buffer.BufferResult;
import io.trino.execution.buffer.LazyOutputBuffer;
import io.trino.execution.buffer.OutputBuffer;
import io.trino.execution.buffer.OutputBuffers;
import io.trino.execution.buffer.OutputBuffers.OutputBufferId;
import io.trino.memory.QueryContext;
import io.trino.operator.PipelineContext;
import io.trino.operator.PipelineStatus;
import io.trino.operator.TaskContext;
import io.trino.operator.TaskStats;
import io.trino.spi.predicate.Domain;
import io.trino.sql.planner.PlanFragment;
import io.trino.sql.planner.plan.DynamicFilterId;
import io.trino.sql.planner.plan.PlanNodeId;
import org.joda.time.DateTime;

import javax.annotation.Nullable;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static com.google.common.base.Verify.verify;
import static com.google.common.util.concurrent.Futures.immediateFuture;
import static com.google.common.util.concurrent.MoreExecutors.directExecutor;
import static io.airlift.units.DataSize.succinctBytes;
import static io.trino.execution.DynamicFiltersCollector.INITIAL_DYNAMIC_FILTERS_VERSION;
import static io.trino.execution.DynamicFiltersCollector.INITIAL_DYNAMIC_FILTER_DOMAINS;
import static io.trino.execution.TaskState.ABORTED;
import static io.trino.execution.TaskState.FAILED;
import static io.trino.execution.TaskState.RUNNING;
import static io.trino.util.Failures.toFailures;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.MILLISECONDS;

public class SqlTask
{
    private static final Logger log = Logger.get(SqlTask.class);

    private final TaskId taskId;
    private final String taskInstanceId;
    private final URI location;
    private final String nodeId;
    private final TaskStateMachine taskStateMachine;
    private final OutputBuffer outputBuffer;
    private final QueryContext queryContext;

    private final SqlTaskExecutionFactory sqlTaskExecutionFactory;
    private final Executor taskNotificationExecutor;

    private final AtomicReference<DateTime> lastHeartbeat = new AtomicReference<>(DateTime.now());
    private final AtomicLong taskStatusVersion = new AtomicLong(TaskStatus.STARTING_VERSION);
    private final FutureStateChange<?> taskStatusVersionChange = new FutureStateChange<>();

    private final AtomicReference<TaskHolder> taskHolderReference = new AtomicReference<>(new TaskHolder());
    private final AtomicBoolean needsPlan = new AtomicBoolean(true);
    private final AtomicReference<String> traceToken = new AtomicReference<>();

    public static SqlTask createSqlTask(
            TaskId taskId,
            URI location,
            String nodeId,
            QueryContext queryContext,
            SqlTaskExecutionFactory sqlTaskExecutionFactory,
            ExecutorService taskNotificationExecutor,
            Consumer<SqlTask> onDone,
            DataSize maxBufferSize,
            DataSize maxBroadcastBufferSize,
            ExchangeManagerRegistry exchangeManagerRegistry,
            CounterStat failedTasks)
    {
        SqlTask sqlTask = new SqlTask(taskId, location, nodeId, queryContext, sqlTaskExecutionFactory, taskNotificationExecutor, maxBufferSize, maxBroadcastBufferSize, exchangeManagerRegistry);
        sqlTask.initialize(onDone, failedTasks);
        return sqlTask;
    }

    private SqlTask(
            TaskId taskId,
            URI location,
            String nodeId,
            QueryContext queryContext,
            SqlTaskExecutionFactory sqlTaskExecutionFactory,
            ExecutorService taskNotificationExecutor,
            DataSize maxBufferSize,
            DataSize maxBroadcastBufferSize,
            ExchangeManagerRegistry exchangeManagerRegistry)
    {
        this.taskId = requireNonNull(taskId, "taskId is null");
        this.taskInstanceId = UUID.randomUUID().toString();
        this.location = requireNonNull(location, "location is null");
        this.nodeId = requireNonNull(nodeId, "nodeId is null");
        this.queryContext = requireNonNull(queryContext, "queryContext is null");
        this.sqlTaskExecutionFactory = requireNonNull(sqlTaskExecutionFactory, "sqlTaskExecutionFactory is null");
        this.taskNotificationExecutor = requireNonNull(taskNotificationExecutor, "taskNotificationExecutor is null");
        requireNonNull(maxBufferSize, "maxBufferSize is null");

        outputBuffer = new LazyOutputBuffer(
                taskId,
                taskInstanceId,
                taskNotificationExecutor,
                maxBufferSize,
                maxBroadcastBufferSize,
                // Pass a memory context supplier instead of a memory context to the output buffer,
                // because we haven't created the task context that holds the memory context yet.
                () -> queryContext.getTaskContextByTaskId(taskId).localMemoryContext(),
                () -> notifyStatusChanged(),
                exchangeManagerRegistry);
        taskStateMachine = new TaskStateMachine(taskId, taskNotificationExecutor);
    }

    // this is a separate method to ensure that the `this` reference is not leaked during construction
    private void initialize(Consumer<SqlTask> onDone, CounterStat failedTasks)
    {
        requireNonNull(onDone, "onDone is null");
        requireNonNull(failedTasks, "failedTasks is null");
        taskStateMachine.addStateChangeListener(newState -> {
            if (!newState.isDone()) {
                if (newState != RUNNING) {
                    // notify that task state changed (apart from initial RUNNING state notification)
                    notifyStatusChanged();
                }
                return;
            }

            // Update failed tasks counter
            if (newState == FAILED) {
                failedTasks.update(1);
            }

            // store final task info
            while (true) {
                TaskHolder taskHolder = taskHolderReference.get();
                if (taskHolder.isFinished()) {
                    // another concurrent worker already set the final state
                    return;
                }

                if (taskHolderReference.compareAndSet(taskHolder, new TaskHolder(
                        createTaskInfo(taskHolder),
                        taskHolder.getIoStats(),
                        taskHolder.getDynamicFilterDomains()))) {
                    break;
                }
            }

            // make sure buffers are cleaned up
            if (newState == FAILED || newState == ABORTED) {
                // don't close buffers for a failed query
                // closed buffers signal to upstream tasks that everything finished cleanly
                outputBuffer.abort();
            }
            else {
                outputBuffer.destroy();
            }

            try {
                onDone.accept(this);
            }
            catch (Exception e) {
                log.warn(e, "Error running task cleanup callback %s", SqlTask.this.taskId);
            }

            // notify that task is finished
            notifyStatusChanged();
        });
    }

    public boolean isOutputBufferOverutilized()
    {
        return outputBuffer.isOverutilized();
    }

    public SqlTaskIoStats getIoStats()
    {
        return taskHolderReference.get().getIoStats();
    }

    public TaskState getTaskState()
    {
        return taskStateMachine.getState();
    }

    public DateTime getTaskCreatedTime()
    {
        return taskStateMachine.getCreatedTime();
    }

    public TaskId getTaskId()
    {
        return taskStateMachine.getTaskId();
    }

    public String getTaskInstanceId()
    {
        return taskInstanceId;
    }

    public void recordHeartbeat()
    {
        lastHeartbeat.set(DateTime.now());
    }

    public TaskInfo getTaskInfo()
    {
        try (SetThreadName ignored = new SetThreadName("Task-%s", taskId)) {
            return createTaskInfo(taskHolderReference.get());
        }
    }

    public TaskStatus getTaskStatus()
    {
        try (SetThreadName ignored = new SetThreadName("Task-%s", taskId)) {
            return createTaskStatus(taskHolderReference.get());
        }
    }

    public VersionedDynamicFilterDomains acknowledgeAndGetNewDynamicFilterDomains(long callersDynamicFiltersVersion)
    {
        return taskHolderReference.get().acknowledgeAndGetNewDynamicFilterDomains(callersDynamicFiltersVersion);
    }

    private synchronized void notifyStatusChanged()
    {
        taskStatusVersion.incrementAndGet();
        taskStatusVersionChange.complete(null, taskNotificationExecutor);
    }

    private TaskStatus createTaskStatus(TaskHolder taskHolder)
    {
        // Obtain task status version before building actual TaskStatus object.
        // This way any task updates won't be lost since all updates happen
        // before version number is increased.
        long versionNumber = taskStatusVersion.get();

        TaskState state = taskStateMachine.getState();
        List<ExecutionFailureInfo> failures = ImmutableList.of();
        if (state == FAILED) {
            failures = toFailures(taskStateMachine.getFailureCauses());
        }

        int queuedPartitionedDrivers = 0;
        long queuedPartitionedSplitsWeight = 0L;
        int runningPartitionedDrivers = 0;
        long runningPartitionedSplitsWeight = 0L;
        DataSize physicalWrittenDataSize = DataSize.ofBytes(0);
        DataSize userMemoryReservation = DataSize.ofBytes(0);
        DataSize peakUserMemoryReservation = DataSize.ofBytes(0);
        DataSize revocableMemoryReservation = DataSize.ofBytes(0);
        long fullGcCount = 0;
        Duration fullGcTime = new Duration(0, MILLISECONDS);
        long dynamicFiltersVersion = INITIAL_DYNAMIC_FILTERS_VERSION;
        if (taskHolder.getFinalTaskInfo() != null) {
            TaskInfo taskInfo = taskHolder.getFinalTaskInfo();
            TaskStats taskStats = taskInfo.getStats();
            queuedPartitionedDrivers = taskStats.getQueuedPartitionedDrivers();
            queuedPartitionedSplitsWeight = taskStats.getQueuedPartitionedSplitsWeight();
            runningPartitionedDrivers = taskStats.getRunningPartitionedDrivers();
            runningPartitionedSplitsWeight = taskStats.getRunningPartitionedSplitsWeight();
            physicalWrittenDataSize = taskStats.getPhysicalWrittenDataSize();
            userMemoryReservation = taskStats.getUserMemoryReservation();
            peakUserMemoryReservation = taskStats.getPeakUserMemoryReservation();
            revocableMemoryReservation = taskStats.getRevocableMemoryReservation();
            fullGcCount = taskStats.getFullGcCount();
            fullGcTime = taskStats.getFullGcTime();
            dynamicFiltersVersion = taskHolder.getDynamicFiltersVersion();
        }
        else if (taskHolder.getTaskExecution() != null) {
            long physicalWrittenBytes = 0;
            TaskContext taskContext = taskHolder.getTaskExecution().getTaskContext();
            for (PipelineContext pipelineContext : taskContext.getPipelineContexts()) {
                PipelineStatus pipelineStatus = pipelineContext.getPipelineStatus();
                queuedPartitionedDrivers += pipelineStatus.getQueuedPartitionedDrivers();
                queuedPartitionedSplitsWeight += pipelineStatus.getQueuedPartitionedSplitsWeight();
                runningPartitionedDrivers += pipelineStatus.getRunningPartitionedDrivers();
                runningPartitionedSplitsWeight += pipelineStatus.getRunningPartitionedSplitsWeight();
                physicalWrittenBytes += pipelineContext.getPhysicalWrittenDataSize();
            }
            physicalWrittenDataSize = succinctBytes(physicalWrittenBytes);
            userMemoryReservation = taskContext.getMemoryReservation();
            revocableMemoryReservation = taskContext.getRevocableMemoryReservation();
            fullGcCount = taskContext.getFullGcCount();
            fullGcTime = taskContext.getFullGcTime();
            dynamicFiltersVersion = taskContext.getDynamicFiltersVersion();
        }

        return new TaskStatus(taskStateMachine.getTaskId(),
                taskInstanceId,
                versionNumber,
                state,
                location,
                nodeId,
                failures,
                queuedPartitionedDrivers,
                runningPartitionedDrivers,
                isOutputBufferOverutilized(),
                physicalWrittenDataSize,
                userMemoryReservation,
                peakUserMemoryReservation,
                revocableMemoryReservation,
                fullGcCount,
                fullGcTime,
                dynamicFiltersVersion,
                queuedPartitionedSplitsWeight,
                runningPartitionedSplitsWeight);
    }

    private TaskStats getTaskStats(TaskHolder taskHolder)
    {
        TaskInfo finalTaskInfo = taskHolder.getFinalTaskInfo();
        if (finalTaskInfo != null) {
            return finalTaskInfo.getStats();
        }
        SqlTaskExecution taskExecution = taskHolder.getTaskExecution();
        if (taskExecution != null) {
            return taskExecution.getTaskContext().getTaskStats();
        }
        // if the task completed without creation, set end time
        DateTime endTime = taskStateMachine.getState().isDone() ? DateTime.now() : null;
        return new TaskStats(taskStateMachine.getCreatedTime(), endTime);
    }

    private static Set<PlanNodeId> getNoMoreSplits(TaskHolder taskHolder)
    {
        TaskInfo finalTaskInfo = taskHolder.getFinalTaskInfo();
        if (finalTaskInfo != null) {
            return finalTaskInfo.getNoMoreSplits();
        }
        SqlTaskExecution taskExecution = taskHolder.getTaskExecution();
        if (taskExecution != null) {
            return taskExecution.getNoMoreSplits();
        }
        return ImmutableSet.of();
    }

    private TaskInfo createTaskInfo(TaskHolder taskHolder)
    {
        // create task status first to prevent potentially seeing incomplete stats for a done task state
        TaskStatus taskStatus = createTaskStatus(taskHolder);
        TaskStats taskStats = getTaskStats(taskHolder);
        Set<PlanNodeId> noMoreSplits = getNoMoreSplits(taskHolder);

        return new TaskInfo(
                taskStatus,
                lastHeartbeat.get(),
                outputBuffer.getInfo(),
                noMoreSplits,
                taskStats,
                Optional.empty(),
                needsPlan.get());
    }

    public synchronized ListenableFuture<TaskStatus> getTaskStatus(long callersCurrentVersion)
    {
        if (callersCurrentVersion < taskStatusVersion.get() || taskHolderReference.get().isFinished()) {
            // return immediately if caller has older task status version or final task info is available
            return immediateFuture(getTaskStatus());
        }

        // At this point taskHolderReference.get().isFinished() might become true. However notifyStatusChanged()
        // is synchronized therefore notification for new listener won't be lost.
        return Futures.transform(taskStatusVersionChange.createNewListener(), input -> getTaskStatus(), directExecutor());
    }

    public synchronized ListenableFuture<TaskInfo> getTaskInfo(long callersCurrentVersion)
    {
        if (callersCurrentVersion < taskStatusVersion.get() || taskHolderReference.get().isFinished()) {
            // return immediately if caller has older task status version or final task info is available
            return immediateFuture(getTaskInfo());
        }

        // At this point taskHolderReference.get().isFinished() might become true. However notifyStatusChanged()
        // is synchronized therefore notification for new listener won't be lost.
        return Futures.transform(taskStatusVersionChange.createNewListener(), input -> getTaskInfo(), directExecutor());
    }

    public TaskInfo updateTask(
            Session session,
            Optional<PlanFragment> fragment,
            List<SplitAssignment> splitAssignments,
            OutputBuffers outputBuffers,
            Map<DynamicFilterId, Domain> dynamicFilterDomains)
    {
        try {
            // trace token must be set first to make sure failure injection for getTaskResults requests works as expected
            session.getTraceToken().ifPresent(traceToken::set);

            // The LazyOutput buffer does not support write methods, so the actual
            // output buffer must be established before drivers are created (e.g.
            // a VALUES query).
            outputBuffer.setOutputBuffers(outputBuffers);

            // assure the task execution is only created once
            SqlTaskExecution taskExecution;
            synchronized (this) {
                // is task already complete?
                TaskHolder taskHolder = taskHolderReference.get();
                if (taskHolder.isFinished()) {
                    return taskHolder.getFinalTaskInfo();
                }
                taskExecution = taskHolder.getTaskExecution();
                if (taskExecution == null) {
                    checkState(fragment.isPresent(), "fragment must be present");
                    taskExecution = sqlTaskExecutionFactory.create(
                            session,
                            queryContext,
                            taskStateMachine,
                            outputBuffer,
                            fragment.get(),
                            this::notifyStatusChanged);
                    taskHolderReference.compareAndSet(taskHolder, new TaskHolder(taskExecution));
                    needsPlan.set(false);
                }
            }

            if (taskExecution != null) {
                taskExecution.addSplitAssignments(splitAssignments);
                taskExecution.getTaskContext().addDynamicFilter(dynamicFilterDomains);
            }
        }
        catch (Error e) {
            failed(e);
            throw e;
        }
        catch (RuntimeException e) {
            failed(e);
        }

        return getTaskInfo();
    }

    public ListenableFuture<BufferResult> getTaskResults(OutputBufferId bufferId, long startingSequenceId, DataSize maxSize)
    {
        requireNonNull(bufferId, "bufferId is null");
        checkArgument(maxSize.toBytes() > 0, "maxSize must be at least 1 byte");

        return outputBuffer.get(bufferId, startingSequenceId, maxSize);
    }

    public void acknowledgeTaskResults(OutputBufferId bufferId, long sequenceId)
    {
        requireNonNull(bufferId, "bufferId is null");

        outputBuffer.acknowledge(bufferId, sequenceId);
    }

    public TaskInfo destroyTaskResults(OutputBufferId bufferId)
    {
        requireNonNull(bufferId, "bufferId is null");

        log.debug("Aborting task %s output %s", taskId, bufferId);
        outputBuffer.destroy(bufferId);

        return getTaskInfo();
    }

    public TaskInfo failed(Throwable cause)
    {
        requireNonNull(cause, "cause is null");

        taskStateMachine.failed(cause);
        return getTaskInfo();
    }

    public TaskInfo cancel()
    {
        taskStateMachine.cancel();
        return getTaskInfo();
    }

    public TaskInfo abort()
    {
        taskStateMachine.abort();
        return getTaskInfo();
    }

    @Override
    public String toString()
    {
        return taskId.toString();
    }

    private static final class TaskHolder
    {
        private final SqlTaskExecution taskExecution;
        private final TaskInfo finalTaskInfo;
        private final SqlTaskIoStats finalIoStats;
        private final VersionedDynamicFilterDomains finalDynamicFilterDomains;

        private TaskHolder()
        {
            this.taskExecution = null;
            this.finalTaskInfo = null;
            this.finalIoStats = null;
            this.finalDynamicFilterDomains = null;
        }

        private TaskHolder(SqlTaskExecution taskExecution)
        {
            this.taskExecution = requireNonNull(taskExecution, "taskExecution is null");
            this.finalTaskInfo = null;
            this.finalIoStats = null;
            this.finalDynamicFilterDomains = null;
        }

        private TaskHolder(TaskInfo finalTaskInfo, SqlTaskIoStats finalIoStats, VersionedDynamicFilterDomains finalDynamicFilterDomains)
        {
            this.taskExecution = null;
            this.finalTaskInfo = requireNonNull(finalTaskInfo, "finalTaskInfo is null");
            this.finalIoStats = requireNonNull(finalIoStats, "finalIoStats is null");
            this.finalDynamicFilterDomains = requireNonNull(finalDynamicFilterDomains, "finalDynamicFilterDomains is null");
        }

        public boolean isFinished()
        {
            return finalTaskInfo != null;
        }

        @Nullable
        public SqlTaskExecution getTaskExecution()
        {
            return taskExecution;
        }

        @Nullable
        public TaskInfo getFinalTaskInfo()
        {
            return finalTaskInfo;
        }

        public SqlTaskIoStats getIoStats()
        {
            // if we are finished, return the final IoStats
            if (finalIoStats != null) {
                return finalIoStats;
            }
            // if we haven't started yet, return an empty IoStats
            if (taskExecution == null) {
                return new SqlTaskIoStats();
            }
            // get IoStats from the current task execution
            TaskContext taskContext = taskExecution.getTaskContext();
            return new SqlTaskIoStats(taskContext.getProcessedInputDataSize(), taskContext.getInputPositions(), taskContext.getOutputDataSize(), taskContext.getOutputPositions());
        }

        public VersionedDynamicFilterDomains acknowledgeAndGetNewDynamicFilterDomains(long callersSummaryVersion)
        {
            // if we are finished, return the final VersionedDynamicFilterDomains
            if (finalDynamicFilterDomains != null) {
                return finalDynamicFilterDomains;
            }
            // if we haven't started yet, return an empty VersionedDynamicFilterDomains
            if (taskExecution == null) {
                return INITIAL_DYNAMIC_FILTER_DOMAINS;
            }
            // get VersionedDynamicFilterDomains from the current task execution
            TaskContext taskContext = taskExecution.getTaskContext();
            return taskContext.acknowledgeAndGetNewDynamicFilterDomains(callersSummaryVersion);
        }

        public long getDynamicFiltersVersion()
        {
            // if we are finished, return the version of the final VersionedDynamicFilterDomains
            if (finalDynamicFilterDomains != null) {
                return finalDynamicFilterDomains.getVersion();
            }
            requireNonNull(taskExecution, "taskExecution is null");
            return taskExecution.getTaskContext().getDynamicFiltersVersion();
        }

        public VersionedDynamicFilterDomains getDynamicFilterDomains()
        {
            verify(finalDynamicFilterDomains == null, "finalDynamicFilterDomains has already been set");
            // Task was aborted or failed before taskExecution was created, return an empty VersionedDynamicFilterDomains
            if (taskExecution == null) {
                return INITIAL_DYNAMIC_FILTER_DOMAINS;
            }
            // get VersionedDynamicFilterDomains from the current task execution
            return taskExecution.getTaskContext().getCurrentDynamicFilterDomains();
        }
    }

    /**
     * Listener is always notified asynchronously using a dedicated notification thread pool so, care should
     * be taken to avoid leaking {@code this} when adding a listener in a constructor. Additionally, it is
     * possible notifications are observed out of order due to the asynchronous execution.
     */
    public void addStateChangeListener(StateChangeListener<TaskState> stateChangeListener)
    {
        taskStateMachine.addStateChangeListener(stateChangeListener);
    }

    public void addSourceTaskFailureListener(TaskFailureListener listener)
    {
        taskStateMachine.addSourceTaskFailureListener(listener);
    }

    public QueryContext getQueryContext()
    {
        return queryContext;
    }

    public Optional<TaskContext> getTaskContext()
    {
        SqlTaskExecution taskExecution = taskHolderReference.get().getTaskExecution();
        if (taskExecution == null) {
            return Optional.empty();
        }
        return Optional.of(taskExecution.getTaskContext());
    }

    public Optional<String> getTraceToken()
    {
        return Optional.ofNullable(traceToken.get());
    }
}
