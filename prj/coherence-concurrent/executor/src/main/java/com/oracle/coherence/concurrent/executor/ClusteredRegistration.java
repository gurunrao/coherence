/*
 * Copyright (c) 2016, 2021, Oracle and/or its affiliates.
 *
 * Licensed under the Universal Permissive License v 1.0 as shown at
 * http://oss.oracle.com/licenses/upl.
 */
package com.oracle.coherence.concurrent.executor;

import com.oracle.coherence.common.base.Logger;

import com.oracle.coherence.concurrent.executor.tasks.CronTask;

import com.oracle.coherence.concurrent.executor.internal.ExecutorTrace;

import com.oracle.coherence.concurrent.executor.util.OptionsByType;

import com.tangosol.net.CacheService;
import com.tangosol.net.NamedCache;

import com.tangosol.util.Filters;
import com.tangosol.util.InvocableMap;
import com.tangosol.util.MapEvent;
import com.tangosol.util.MapListener;
import com.tangosol.util.ValueExtractor;

import com.tangosol.util.extractor.MultiExtractor;
import com.tangosol.util.extractor.ReflectionExtractor;

import com.tangosol.util.processor.ConditionalPut;
import com.tangosol.util.processor.ExtractorProcessor;

import java.util.EnumSet;
import java.util.List;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.oracle.coherence.concurrent.executor.TaskExecutorService.ExecutorInfo;

/**
 * A cluster-based implementation of an {@link TaskExecutorService.Registration}.
 *
 * @author bo
 * @since 21.12
 */
@SuppressWarnings("rawtypes")
class ClusteredRegistration
        implements TaskExecutorService.Registration, MapListener
    {
    // ----- constructors ---------------------------------------------------

    /**
     * Constructs a {@link ClusteredRegistration}.
     *
     * @param clusteredExecutorService  the {@link TaskExecutorService} that owns the
     *                                  {@link TaskExecutorService.Registration}
     * @param sExecutorId               the identity of the registered {@link Executor}
     * @param executor                  the registered {@link Executor}
     * @param optionsByType             the {@link Option}s for the {@link Executor}
     */
    public ClusteredRegistration(ClusteredExecutorService clusteredExecutorService, String sExecutorId,
            Executor executor, OptionsByType<Option> optionsByType)
        {
        f_clusteredExecutorService = clusteredExecutorService;
        f_sExecutorId              = sExecutorId;
        f_executor                 = executor;
        f_optionsByType            = optionsByType;
        f_mapTaskExecutors         = new ConcurrentHashMap<>();
        }

    // ----- TaskExecutorService.Registration interface ---------------------

    @Override
    public String getId()
        {
        return f_sExecutorId;
        }

    @Override
    public <T extends Option> T getOption(Class<T> classOfOption,
                                          T defaultIfNotFound)
        {
        return f_optionsByType.get(classOfOption, defaultIfNotFound);
        }

    // ----- accessors ------------------------------------------------------

    /**
     * Return the number of completed tasks.
     *
     * @return the number of completed tasks.
     */
    public long getTasksCompletedCount()
        {
        return m_cTasksCompletedCount;
        }

    /**
     * Return the number of failed tasks.
     *
     * @return the number of failed tasks.
     */
    public long getTasksFailedCount()
        {
        return m_cTasksFailedCount;
        }

    /**
     * Return the number of tasks in progress.
     *
     * @return the number of tasks in progress
     */
    public long getTasksInProgressCount()
        {
        return m_cTasksInProgressCount;
        }

   // ----- MapListener interface -------------------------------------------

    @SuppressWarnings("checkstyle:EmptyBlock")
    @Override
    public void entryInserted(MapEvent mapEvent)
        {
        ExecutorTrace.log(() -> String.format("Executor [%s] received insert event [%s]", f_sExecutorId, mapEvent));

        // obtain the task assignment
        final ClusteredAssignment assignment = (ClusteredAssignment) mapEvent.getNewValue();

        // obtain the identity of the assigned task and executor
        String sTaskId = assignment.getTaskId();
        String sExecId = assignment.getExecutorId();

        // establish a TaskExecutor for the assigned task
        TaskExecutor taskExecutor = new TaskExecutor(sTaskId, assignment.isRecovered());

        TaskExecutor existing = f_mapTaskExecutors.putIfAbsent(sTaskId, taskExecutor);

        m_cTasksInProgressCount++;

        if (existing == null)
            {
            // submit the TaskExecutor for execution using the ExecutionService
            executingTask(taskExecutor, sTaskId, sExecId);
            }
        else
            {
            // TODO: this is pretty bad as it means we've received an insert event for task we're already executing
            // TODO: update the existing task state?
            }
        }

    @Override
    public void entryUpdated(MapEvent mapEvent)
        {
        // we don't need to do anything when a Task assignment is updated
        // (as it's ourselves doing the update)
        ExecutorTrace.log(() -> String.format("Executor [%s] received update event [%s]", f_sExecutorId, mapEvent));
        }

    @Override
    public void entryDeleted(MapEvent mapEvent)
        {
        ExecutorTrace.log(() -> String.format("Executor [%s] received deleted event [%s]", f_sExecutorId, mapEvent));

        // obtain the task assignment
        ClusteredAssignment assignment = (ClusteredAssignment) mapEvent.getOldValue();

        // obtain the identity of the assigned task
        String taskId = assignment.getTaskId();

        // remove the task
        f_mapTaskExecutors.remove(taskId);

        m_cTasksCompletedCount++;
        m_cTasksInProgressCount--;
        }

    /**
     * Gracefully close the {@link Executor}.  Existing assigned tasks will not be canceled.
     */
    @SuppressWarnings("unchecked")
    public void shutdown()
        {
        if (f_fShutdownCalled.compareAndSet(false, true))
            {
            // only go through the shutdown process once

            f_clusteredExecutorService.getCacheService()
                    .ensureCache(ClusteredExecutorInfo.CACHE_NAME, null)
                            .invoke(f_sExecutorId,
                                    new ClusteredExecutorInfo.SetStateProcessor(ExecutorInfo.State.CLOSING_GRACEFULLY));

            // schedule a callable that will touch the executor every second within the cluster using the
            // local ScheduledExecutorService from the ClusteredExecutorService. This will trigger a check
            // for remaining tasks
            try
                {
                m_touchFuture =
                    f_clusteredExecutorService.getScheduledExecutorService()
                        .scheduleAtFixedRate(
                                new ClusteredExecutorInfo.TouchRunnable(f_sExecutorId,
                                        f_clusteredExecutorService.getCacheService()), 1, 1, TimeUnit.SECONDS);
                }
            catch (RejectedExecutionException e)
                {
                // ignore - likely an Executor being de-registered in the middle of the
                // ClusteredExecutorService shutting down
                }
            }
        }

    // ----- inner class TaskExecutor --------------------------------------

    /**
     * A {@link Runnable} to execute an assigned {@link Task}.
     */
    protected class TaskExecutor
            implements Runnable, Task.Context
        {
        // ----- constructors -----------------------------------------------

        /**
         * Constructs a {@link TaskExecutor}.
         *
         * @param sTaskId     the identity of the {@link Task} to execute
         * @param fRecovered  if the {@link Task} was previously assigned to a different {@link Executor}
         */
        public TaskExecutor(String sTaskId, boolean fRecovered)
            {
            f_sTaskId    = sTaskId;
            m_task       = null;
            m_cYield     = 0;
            m_properties = null;
            f_fRecovered = fRecovered;
            }

        // ----- public methods  --------------------------------------------

        /**
         * Set the result processing result.
         *
         * @param result  the result to report
         */
        public void setResult(Result result)
            {
            setResult(result, false);
            }

        /**
         * Set the result processing result.
         *
         * @param result    the result to report
         * @param fComplete whether execution is completed
         */
        @SuppressWarnings("unchecked")
        public void setResult(Result result, boolean fComplete)
            {
            if (result == null)
                {
                result = Result.none();
                }

            final Result resultLocal = result;

            ExecutorTrace.log(() -> String.format("Executor [%s] setting the execution result of Task [%s]: %s",
                                            f_sExecutorId, f_sTaskId, resultLocal));

            InvocableMap.EntryProcessor processor =
                    new ClusteredTaskManager.UpdateContributedResultProcessor(f_sExecutorId, resultLocal);

            if (fComplete)
                {
                // update state and notify that the execution has completed
                ClusteredTaskManager.ChainedProcessor cp = ClusteredTaskManager.ChainedProcessor.empty();
                cp.andThen(processor);

                cp.andThen(new ClusteredTaskManager.SetActionProcessor(f_sExecutorId, ExecutionPlan.Action.COMPLETED));
                processor = cp;
                }

            // acquire the task
            NamedCache tasksCache = f_clusteredExecutorService.getCacheService()
                    .ensureCache(ClusteredTaskManager.CACHE_NAME, null);

            // update the Task with the result
            tasksCache.invoke(f_sTaskId, processor);
            }

        // ----- Runnable interface -----------------------------------------

        @SuppressWarnings("unchecked")
        @Override
        public void run()
            {
            // should the execution status be updated after the task has executed?
            boolean fUpdateExecutionStatus;

            // should the resources to track the execution locally be cleaned up?
            boolean fCleanupLocalExecutionResources;

            // ensure the TaskExecutor still exists
            // (if it doesn't, the executor has been removed, thus we can skip execution)
            if (f_mapTaskExecutors.containsKey(f_sTaskId))
                {
                ExecutorTrace.log(() -> String.format("Executor [%s] preparing execution of Task [%s]",
                                                f_sExecutorId, f_sTaskId));

                // acquire the task
                NamedCache tasksCache =
                    f_clusteredExecutorService.getCacheService().ensureCache(ClusteredTaskManager.CACHE_NAME,
                                                                             null);

                // whether the task execution is considered completed by the ClusteredExecutorService
                boolean fIsCompleted;

                // when resuming locally, just determine if we've completed the task
                if (isResuming() && (!f_fRecovered || m_task instanceof CronTask))
                    {
                    // when resuming, only extract the necessary information (like isCompleted)
                    List listExtracted = (List) tasksCache.invoke(f_sTaskId,
                            new ExtractorProcessor(
                                    new MultiExtractor(new ValueExtractor[] {new ReflectionExtractor("isCompleted")})));

                    // ensure the TaskExecutor still exists
                    // (if it doesn't, the executor has been removed, thus we can skip execution)
                    Object oIsCompleted =
                            listExtracted == null || listExtracted.size() < 1 ? null : listExtracted.get(0);

                    if (oIsCompleted == null)
                        {
                        m_task       = null;
                        fIsCompleted = true;
                        }
                    else
                        {
                        fIsCompleted = (Boolean) listExtracted.get(0);
                        }
                    }
                else
                    {
                    // otherwise, extract the task and completion status
                    List listExtracted = (List) tasksCache.invoke(f_sTaskId,
                            new ExtractorProcessor(new MultiExtractor(new ValueExtractor[] {
                                    new ReflectionExtractor("getTask"),
                                    new ReflectionExtractor("isCompleted")})));

                    Object oTask = listExtracted == null || listExtracted.size() < 2 ? null : listExtracted.get(0);

                    if (oTask == null)
                        {
                        // extractor may return null values when the entry is no longer present
                        m_task       = null;
                        fIsCompleted = true;
                        }
                    else
                        {
                        m_task       = (Task) oTask;
                        fIsCompleted = (Boolean) listExtracted.get(1);
                        }
                    }

                // ensure the task still exists
                // (if it doesn't, the executor has been removed, thus we can skip execution)
                if (m_task == null)
                    {
                    ExecutorTrace.log(() -> String.format("Executor [%s] skipping execution of Task [%s] (no longer exists)",
                                                    f_sExecutorId, f_sTaskId));

                    // we skip executing the task as it has been completed
                    fUpdateExecutionStatus          = false;
                    fCleanupLocalExecutionResources = true;
                    }

                // ensure the task still exists
                // (if it doesn't, the executor has been removed, thus we can skip execution)
                else if (fIsCompleted)
                    {
                    ExecutorTrace.log(() -> String.format(
                            "Executor [%s] skipping execution of Task [%s] (it's completed or cancelled)",
                            f_sExecutorId, f_sTaskId));

                    // we skip executing the task as it has been completed
                    fUpdateExecutionStatus          = true;
                    fCleanupLocalExecutionResources = true;
                    }
                else
                    {
                    // update the task assignment to indicate that we've started execution
                    ClusteredAssignment.State existing = (ClusteredAssignment.State) m_assignmentsCache
                            .invoke(ClusteredAssignment.getCacheKey(f_sExecutorId, f_sTaskId),
                                    new ClusteredAssignment.SetStateProcessor(ClusteredAssignment.State.ASSIGNED,
                                            ClusteredAssignment.State.EXECUTING));

                    if (existing == null)
                        {
                        // the assignment no longer exists, so we can skip execution
                        fUpdateExecutionStatus          = true;
                        fCleanupLocalExecutionResources = true;
                        }
                    else if (existing.equals(ClusteredAssignment.State.ASSIGNED)
                             || (isResuming() && existing.equals(ClusteredAssignment.State.EXECUTING)))
                        {
                        // attempt to execute the task (passing in this executor as the context)
                        try
                            {
                            ExecutorTrace.log(() -> String.format("Executor [%s] Task [%s]",
                                                            isResuming() ? "Resuming" : "Executing", f_sTaskId));

                            setResult(Result.of(m_task.execute(this)), true);

                            // we've provided a result; assume we're finished executing
                            fUpdateExecutionStatus          = true;
                            fCleanupLocalExecutionResources = true;
                            }
                        catch (Task.Yield yield)
                            {
                            // increase the yield count (to indicate that we've yielded)
                            m_cYield++;

                            ExecutorTrace.log(() -> String.format("Executor [%s] scheduling Task [%s] to resume in %s",
                                                            f_sExecutorId, f_sTaskId, yield.getDuration()));

                            TaskExecutor taskExecutor = this;
                            f_clusteredExecutorService.getScheduledExecutorService()
                                    .schedule(() -> executingTask(taskExecutor, f_sTaskId, f_sExecutorId),
                                            yield.getDuration().toNanos(), TimeUnit.NANOSECONDS);

                            // we've yielded so we're not finished
                            fUpdateExecutionStatus          = false;
                            fCleanupLocalExecutionResources = false;
                            }
                        catch (Throwable throwable)
                            {
                            // update the result indicating the exception
                            setResult(Result.throwable(throwable));

                            // we're finished executing!
                            fUpdateExecutionStatus          = true;
                            fCleanupLocalExecutionResources = true;
                            }
                        }
                    else
                        {
                        // TODO: the assignment state wasn't as expected, which means some other thread
                        // with the same identity (possibly a recovered thread) was executing or had
                        // executed the task.
                        fUpdateExecutionStatus          = false;
                        fCleanupLocalExecutionResources = true;
                        }
                    }

                if (fUpdateExecutionStatus)
                    {
                    ExecutorTrace.log(() -> String.format(
                            "Executor [%s] updating execution state for Task [%s] (now EXECUTED)",
                            f_sExecutorId, f_sTaskId));

                    // update the task assignment to indicate that we've completed execution
                    NamedCache cache = m_assignmentsCache;

                    // may be null or not active if this ClusteredExecutorService is shutting down
                    if (cache != null && cache.isActive())
                        {
                        try
                            {
                            cache.invoke(ClusteredAssignment.getCacheKey(f_sExecutorId, f_sTaskId),
                                         new ClusteredAssignment.SetStateProcessor(ClusteredAssignment.State.EXECUTING,
                                                                                   ClusteredAssignment.State.EXECUTED));
                            }
                        catch (IllegalStateException e)
                            {
                            if (!f_fShutdownCalled.get())
                                {
                                // unexpected if we're not in shutdown
                                throw e;
                                }
                            }
                        }
                    }

                if (fCleanupLocalExecutionResources)
                    {
                    ExecutorTrace.log(() -> String.format("Executor [%s] cleaning up local resources for Task [%s]",
                                                    f_sExecutorId, f_sTaskId));

                    // stop tracking the executor locally
                    f_mapTaskExecutors.remove(f_sTaskId);
                    }
                }
            else
                {
                ExecutorTrace.log(() -> String.format(
                        "Executor [%s] skipping execution of Task [%s] (no longer tracked locally)",
                        f_sExecutorId, f_sTaskId));
                }
            }

        // ----- Context interface ------------------------------------------

        @Override
        public void setResult(Object result)
            {
            setResult(Result.of(result));
            }

        @SuppressWarnings("unchecked")
        @Override
        public boolean isDone()
            {
            Boolean fDone = (Boolean) f_clusteredExecutorService.getCacheService()
                    .ensureCache(ClusteredTaskManager.CACHE_NAME, null)
                            .invoke(f_sTaskId, new ExtractorProcessor("isDone"));

            return fDone == null || fDone;
            }

        @Override
        public boolean isResuming()
            {
            return m_cYield > 0 || f_fRecovered;
            }

        @Override
        public Task.Properties getProperties()
            {
            synchronized (this)
                {
                if (m_properties == null)
                    {
                    m_properties = (ClusteredProperties) f_clusteredExecutorService.acquire(f_sTaskId).getProperties();
                    }
                }

            return m_properties;
            }

        @Override
        public String getTaskId()
            {
            return f_sTaskId;
            }

        @Override
        public String getExecutorId()
            {
            return ClusteredRegistration.this.getId();
            }

        // ----- data members -----------------------------------------------

        /**
         * The identity of the {@link Task} to execute.
         */
        protected final String f_sTaskId;

        /**
         * The {@link Task} to execute.
         */
        protected Task m_task;

        /**
         * The number of times the {@link TaskExecutor} has been yielded.
         */
        protected int m_cYield;

        /**
         * Indicates if the {@link Task} was previously assigned to a different {@link Executor}.
         */
        protected final boolean f_fRecovered;

        /**
         * The task {@link Task.Properties}.
         */
        protected ClusteredProperties m_properties;
        }

    // ----- helper methods -------------------------------------------------

    /**
     * Execute the task and handle error/exception.
     *
     * @param taskExecutor  the {@link TaskExecutor}
     * @param sExecId       the executor ID
     * @param sTaskId       the task ID
     */
    @SuppressWarnings("unchecked")
    protected void executingTask(TaskExecutor taskExecutor, String sExecId, String sTaskId)
        {
        // submit the TaskExecutor for execution using the ExecutionService
        try
            {
            f_executor.execute(taskExecutor);
            }
        catch (RejectedExecutionException e)
            {
            Logger.info(() -> String.format("Executor [%s] rejected Task [%s]", sExecId, sTaskId));

            Logger.info("Task rejected due to RejectedExecutionException", e);

            // set the result the throwable
            taskExecutor.setResult(Result.throwable(e), true);
            m_cTasksFailedCount++;

            // update the ExecutorInfo to indicate that is it now rejecting tasks
            f_clusteredExecutorService.getScheduledExecutorService().submit(() ->
                {
                f_clusteredExecutorService.getCacheService()
                    .ensureCache(ClusteredExecutorInfo.CACHE_NAME, null)
                            .invoke(sExecId, new ClusteredExecutorInfo.SetStateProcessor(ExecutorInfo.State.RUNNING,
                                    ExecutorInfo.State.REJECTING));
                });

            // update the execution plan action and notify TaskManager about the change so that the
            // task that used the Executor can be re-assigned.
            ClusteredTaskManager.ChainedProcessor chainedProcessor = ClusteredTaskManager.ChainedProcessor.empty();

            chainedProcessor.andThen(new ClusteredTaskManager
                    .SetActionProcessor(sExecId, EnumSet.of(
                            ExecutionPlan.Action.ASSIGN, ExecutionPlan.Action.RECOVER), ExecutionPlan.Action.REASSIGN));

            chainedProcessor.andThen(new ClusteredTaskManager.NotifyExecutionStrategyProcessor());
            f_clusteredExecutorService.getCacheService()
                    .ensureCache(ClusteredTaskManager.CACHE_NAME, null).invoke(sTaskId, chainedProcessor);

            if (f_executor instanceof ExecutorService)
                {
                ExecutorService executorService = (ExecutorService) f_executor;

                if (executorService.isShutdown() || executorService.isTerminated())
                    {
                    Logger.info(() -> String.format(
                            "Executor [%s] rejected Task [%s] due to shutdown", sExecId, sTaskId));

                    // deregister the service
                    f_clusteredExecutorService.deregister(f_executor);
                    }
                }
            }
        }

    /**
     * Starts the {@link TaskExecutorService.Registration} for the {@link Executor}, allowing assigned {@link Task}s to
     * be executed and {@link TaskExecutorService.ExecutorInfo} state to be updated.
     */
    @SuppressWarnings("unchecked")
    protected void start()
        {
        // only start if when we haven't already started
        if (m_scheduledFuture == null)
            {
            // obtain the CacheService from the ClusteredExecutorService
            // (so that we can create and update cache information)
            CacheService service = f_clusteredExecutorService.getCacheService();

            // register a listener to detect external closing of the Executor
            service.ensureCache(ClusteredExecutorInfo.CACHE_NAME, null).addMapListener(f_listener, f_sExecutorId, true);

            // create a view for the task assignments to the Executor
            NamedCache clusteredAssignment = service.ensureCache(ClusteredAssignment.CACHE_NAME, null);
            m_assignmentsCache = clusteredAssignment.view()
                    .filter(Filters.equal("executorId", f_sExecutorId))
                    .listener(this)
                    .build();

            // acquire the executor info cache
            NamedCache cache = service.ensureCache(ClusteredExecutorInfo.CACHE_NAME, null);

            // acquire the Runtime so we can capture local runtime information
            Runtime runtime = Runtime.getRuntime();

            // attempt to create the information for the executor
            ClusteredExecutorInfo info =
                    new ClusteredExecutorInfo(f_sExecutorId, System.currentTimeMillis(), runtime.maxMemory(),
                            runtime.totalMemory(), runtime.freeMemory(), f_optionsByType);

            // attempt to create the cluster information for the executor
            ClusteredExecutorInfo existingInfo = (ClusteredExecutorInfo)
                    cache.invoke(f_sExecutorId, new ConditionalPut(Filters.not(Filters.present()), info, true));

            if (existingInfo == null)
                {
                // schedule a callable to update the state of the executor in the cluster using the
                // local ScheduledExecutorService from the ClusteredExecutorService
                m_scheduledFuture =
                        f_clusteredExecutorService.getScheduledExecutorService()
                                .scheduleAtFixedRate(
                                        new ClusteredExecutorInfo.UpdateInfoRunnable(
                                                service, f_sExecutorId, f_executor, this), INFO_UPDATE_DELAY,
                                                INFO_UPDATE_DELAY, INFO_UPDATE_DELAY_UNIT);
                }
            else if (existingInfo != null)
                {
                // TODO: we need to ensure that the existing registered Executor is actually registered locally!
                // (if not someone has attempted to register an execution service somewhere else with the same identity)
                }
            }
        }

    /**
     * Closes the {@link Executor}.
     */
    @SuppressWarnings("unchecked")
    protected void close()
        {
        if (f_fCloseCalled.compareAndSet(false, true))
            {
            // only go through the close process once

            if (m_scheduledFuture != null && !m_scheduledFuture.isDone())
                {
                // interrupt and cancel the ScheduledFuture
                m_scheduledFuture.cancel(true);
                }

            if (m_touchFuture != null && !m_touchFuture.isDone())
                {
                m_touchFuture.cancel(true);
                }

            // schedule the ExecutorInfo to be in "shutting down" state
            // (that will allow the server to clean up)
            // change the state of the Executor to be Running

            // NOTE: we have to do this asynchronously as this method may be called
            //       by a Coherence-thread

            // don't use the local ClusteredExecutorService's ExecutorService as it may
            // be in the process of shutting down
            ThreadFactories.createThreadFactory(true, "Cleanup", null).newThread(() ->
                {
                NamedCache esCache =
                        f_clusteredExecutorService.getCacheService()
                                .ensureCache(ClusteredExecutorInfo.CACHE_NAME, null);

                // deregister Executor entry listener
                esCache.removeMapListener(f_listener, f_sExecutorId);

                esCache.invoke(f_sExecutorId, new ClusteredExecutorInfo.SetStateProcessor(ExecutorInfo.State.CLOSING));
                }).start();

            // release the assignments cache (as it's a CQC)
            if (m_assignmentsCache != null)
                {
                m_assignmentsCache.release();

                m_assignmentsCache = null;
                }

            // de-register in case the close wasn't initiated by the owning ClusteredExecutorService
            f_clusteredExecutorService.deregister(f_executor);
            }
        }

    // ----- constants ------------------------------------------------------

    /**
     * The delay between attempts to update the {@link TaskExecutorService.ExecutorInfo}.
     */
    public static long INFO_UPDATE_DELAY = 5;

    /**
     * The delay {@link TimeUnit} for {@link #INFO_UPDATE_DELAY}.
     */
    public static TimeUnit INFO_UPDATE_DELAY_UNIT = TimeUnit.SECONDS;

    // ----- data members ---------------------------------------------------

    /**
     * Listener to detect changes on the cache entry for the {@link Executor}.
     */
    @SuppressWarnings("rawtypes")
    protected final MapListener f_listener = new MapListener()
        {
        // ----- MapListener interface --------------------------------------

        @Override
        public void entryInserted(MapEvent mapEvent)
            {
            // we ignore inserts
            }

        @Override
        public void entryUpdated(MapEvent mapEvent)
            {
            // we ignore updates
            }

        @Override
        public void entryDeleted(MapEvent mapEvent)
            {
            // close local resources when we're deleted
            close();
            }
        };

    /**
     * Track whether {@link #shutdown()} has been called.
     */
    protected final AtomicBoolean f_fShutdownCalled = new AtomicBoolean(false);

    /**
     * Track whether {@link #close()} has been called.
     */
    protected final AtomicBoolean f_fCloseCalled = new AtomicBoolean(false);

    /**
     * The tasks completed count.
     */
    protected long m_cTasksCompletedCount = 0;

    /**
     * The tasks failed count.
     */
    protected long m_cTasksFailedCount = 0;

    /**
     * The tasks in progress count.
     */
    protected long m_cTasksInProgressCount = 0;

    /**
     * The {@link TaskExecutorService} that created the {@link TaskExecutorService.Registration}.
     */
    protected final ClusteredExecutorService f_clusteredExecutorService;

    /**
     * The identity of the registered {@link Executor}.
     */
    protected final String f_sExecutorId;

    /**
     * The local {@link Executor} that was registered.
     */
    protected final Executor f_executor;

    /**
     * The {@link TaskExecutorService.Registration.Option}s for the {@link Executor}.
     */
    protected final OptionsByType<Option> f_optionsByType;

    /**
     * A {@link ScheduledFuture} representing the {@link TaskExecutorService.ExecutorInfo} updater to update the status
     * in the cluster.
     */
    @SuppressWarnings("rawtypes")
    protected volatile ScheduledFuture m_scheduledFuture;

    /**
     * A {@link ScheduledFuture} representing the {@link TaskExecutorService.ExecutorInfo} touch updater to trigger
     * checking for remaining assigned tasks during graceful close.
     */
    @SuppressWarnings("rawtypes")
    protected volatile ScheduledFuture m_touchFuture;

    /**
     * The {@link NamedCache} containing {@link ClusteredAssignment}s for the {@link Executor}.
     */
    @SuppressWarnings("rawtypes")
    protected NamedCache m_assignmentsCache;

    /**
     * The {@link TaskExecutor}s representing the {@link Task}s scheduled for execution with the {@link Executor}.
     */
    protected final ConcurrentHashMap<String, TaskExecutor> f_mapTaskExecutors;
    }