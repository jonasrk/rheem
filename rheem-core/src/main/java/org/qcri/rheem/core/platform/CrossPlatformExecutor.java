package org.qcri.rheem.core.platform;

import org.qcri.rheem.core.api.Job;
import org.qcri.rheem.core.api.exception.RheemException;
import org.qcri.rheem.core.plan.executionplan.Channel;
import org.qcri.rheem.core.plan.executionplan.ExecutionPlan;
import org.qcri.rheem.core.plan.executionplan.ExecutionStage;
import org.qcri.rheem.core.plan.executionplan.PlatformExecution;
import org.qcri.rheem.core.profiling.InstrumentationStrategy;
import org.qcri.rheem.core.util.Counter;
import org.qcri.rheem.core.util.Formats;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Executes a (cross-platform) {@link ExecutionPlan}.
 */
public class CrossPlatformExecutor {

    public final Logger logger = LoggerFactory.getLogger(this.getClass());


    /**
     * The {@link Job} that is being executed by this instance.
     */
    private final Job job;

    /**
     * Aggregates user-defined {@link Breakpoint}s. Will be cleared after each execution.
     */
    private ConjunctiveBreakpoint breakpoint = new ConjunctiveBreakpoint();

    /**
     * Number of completed predecessors per {@link ExecutionStage}.
     */
    private Counter<ExecutionStage> predecessorCounter = new Counter<>();

    /**
     * Activated and considered for execution.
     */
    private Queue<ExecutionStage> activatedStages = new LinkedList<>();

    /**
     * Maintains the {@link Executor}s for each {@link PlatformExecution}.
     */
    private Map<PlatformExecution, Executor> executors = new HashMap<>();

    /**
     * The number of executed {@link ExecutionStage}s per {@link PlatformExecution}.
     */
    private Counter<PlatformExecution> executionStageCounter = new Counter<>();

    /**
     * We keep them around if we want to go on without re-optimization.
     */
    private Collection<ExecutionStage> suspendedStages = new LinkedList<>();

    /**
     * Marks {@link Channel}s for instrumentation.
     */
    private final InstrumentationStrategy instrumentationStrategy;


    /**
     * Keeps track of {@link ExecutionStage}s that have actually been executed by this instance.
     */
    private Set<ExecutionStage> completedStages = new HashSet<>();

    private ExecutionState executionState;

    public CrossPlatformExecutor(Job job, InstrumentationStrategy instrumentationStrategy) {
        this.job = job;
        this.instrumentationStrategy = instrumentationStrategy;
    }

    /**
     * Execute the given {@link ExecutionPlan}.
     */
    public State executeUntilBreakpoint(ExecutionPlan executionPlan) {
        this.prepare(executionPlan);
        this.runToBreakpoint();
        this.breakpoint = new ConjunctiveBreakpoint();
        return this.captureState();
    }

    /**
     * Prepare this instance to fast-forward already executed parts of the new {@link ExecutionPlan}.
     */
    public void prepare(ExecutionPlan executionPlan) {
        this.predecessorCounter.clear();
        this.executionStageCounter.clear();

        this.activatedStages.clear();
        this.suspendedStages.clear();
        this.activatedStages.addAll(executionPlan.getStartingStages());

        this.executionState = new ExecutionState();
    }

    private void runToBreakpoint() {
        // Start execution traversal.
        final long startTime = System.currentTimeMillis();
        int numExecutedStages = 0, numSkippedStages = 0;
        Collection<ExecutionStage> newlyActivatedStages = new LinkedList<>();
        int lastNumSkippedStages = 0;
        boolean isBreakpointsDisabled = false;
        do {
            while (!this.activatedStages.isEmpty()) {
                final ExecutionStage nextStage = this.activatedStages.poll();

                // Check if #breakpoint permits the execution.
                if (!nextStage.wasExecuted()
                        && !isBreakpointsDisabled
                        && this.suspendIfBreakpointRequest(nextStage)) {
                    continue;
                }

                // Otherwise, execute the stage.
                if (this.execute(nextStage)) {
                    numExecutedStages++;
                } else {
                    numSkippedStages++;
                }
                this.tryToActivateSuccessors(nextStage, newlyActivatedStages);
            }
            // Safety net to recover from illegal Breakpoint configurations.
            if (!isBreakpointsDisabled && numExecutedStages == 0 && numSkippedStages == lastNumSkippedStages) {
                this.logger.warn("Could not execute a single stage. Will retry with disabled breakpoints.");
                isBreakpointsDisabled = true;
                this.activatedStages.addAll(this.suspendedStages);
                this.suspendedStages.clear();
            } else {
                this.activatedStages.addAll(newlyActivatedStages);
                newlyActivatedStages.clear();
                isBreakpointsDisabled = false;
                lastNumSkippedStages = numSkippedStages;
            }
        } while (!this.activatedStages.isEmpty());
        final long finishTime = System.currentTimeMillis();
        CrossPlatformExecutor.this.logger.info("Executed {} stages (and skipped {}) in {}.",
                numExecutedStages, numSkippedStages, Formats.formatDuration(finishTime - startTime));

        // Sanity check. TODO: Should be an assertion, shouldn't it?
        if (numExecutedStages == 0) {
            throw new RheemException("Could not execute a single stage. Are the Rheem plan and breakpoints correct?");
        }

        this.breakpoint = new ConjunctiveBreakpoint();
    }


    private boolean suspendIfBreakpointRequest(ExecutionStage activatedStage) {
        if (CrossPlatformExecutor.this.breakpoint != null &&
                !CrossPlatformExecutor.this.breakpoint.permitsExecutionOf(activatedStage)) {
            this.suspendedStages.add(activatedStage);
            return true;
        }
        return false;
    }

    /**
     * Tries to execute the given {@link ExecutionStage}.
     *
     * @return whether the {@link ExecutionStage} was really executed
     */
    private boolean execute(ExecutionStage activatedStage) {
        final boolean shouldExecute = !activatedStage.wasExecuted();
        if (shouldExecute) {
            this.instrumentationStrategy.applyTo(activatedStage);
            Executor executor = this.getOrCreateExecutorFor(activatedStage);
            final ExecutionState executionState = this.submit(activatedStage, executor);
            this.executionState.merge(executionState);
            activatedStage.setWasExecuted(true);
            this.completedStages.add(activatedStage);

        } else {
            CrossPlatformExecutor.this.logger.debug("Skipping already executed {}.", activatedStage);
        }

        this.disposeExecutorIfDone(activatedStage.getPlatformExecution());

        return shouldExecute;
    }

    private Executor getOrCreateExecutorFor(ExecutionStage stage) {
        return this.executors.computeIfAbsent(
                stage.getPlatformExecution(),
                pe -> pe.getPlatform().getExecutorFactory().create(this.job)
        );
    }

    /**
     * Submit the {@code stage} to the {@code executor}.
     *
     * @return the {@link ExecutionState} that has been gathered during execution
     */
    private ExecutionState submit(ExecutionStage stage, Executor executor) {
        CrossPlatformExecutor.this.logger.info("Start executing {}.", stage);
        CrossPlatformExecutor.this.logger.info("Stage plan:\n{}", stage.toExtensiveString());
        long startTime = System.currentTimeMillis();
        final ExecutionState executionState = executor.execute(stage, this.executionState);
        long finishTime = System.currentTimeMillis();
        CrossPlatformExecutor.this.logger.info("Executed {} in {}.", stage, Formats.formatDuration(finishTime - startTime));
//        throw new RuntimeException("todo");
//        executionState.getCardinalities().forEach((channel, cardinality) ->
//                CrossPlatformExecutor.this.logger.debug("Cardinality of {}: actual {}, estimated {}",
//                        channel, cardinality, channel.getCardinalityEstimate(optimizationContext))
//        );
        return executionState;
    }

    /**
     * Increments the {@link #executionStageCounter} for the given {@link PlatformExecution} and disposes
     * {@link Executor}s if it is no longer needed.
     */
    private void disposeExecutorIfDone(PlatformExecution platformExecution) {
        int numExecutedStages = this.executionStageCounter.increment(platformExecution);
        if (numExecutedStages == platformExecution.getStages().size()) {
            // Be cautious: In replay mode, we do not want to re-summon the Executor.
            final Executor executor = this.executors.get(platformExecution);
            if (executor != null) {
                executor.dispose();
                this.executors.remove(platformExecution);
            }
        }
    }

    /**
     * Increments the {@link #predecessorCounter} for all successors of the given {@link ExecutionStage} and
     * activates them if possible by putting them in the given {@link Collection}.
     */
    private void tryToActivateSuccessors(ExecutionStage processedStage, Collection<ExecutionStage> collector) {
        for (ExecutionStage succeedingStage : processedStage.getSuccessors()) {
            final int newCompletedPredecessors = this.predecessorCounter.increment(succeedingStage);
            this.logger.info("{} activated {} to {}/{}.", processedStage, succeedingStage, newCompletedPredecessors,
                    succeedingStage.getPredecessors().size());
            if (newCompletedPredecessors == succeedingStage.getPredecessors().size()) {
                collector.add(succeedingStage);
                this.predecessorCounter.remove(succeedingStage);
            } else {
                assert newCompletedPredecessors < succeedingStage.getPredecessors().size() :
                String.format("Activated %s too often.", succeedingStage);
            }
        }
    }

    public void extendBreakpoint(Breakpoint breakpoint) {
        this.breakpoint.addConjunct(breakpoint);
    }

    /**
     * @return the internal state of this instance
     */
    public State captureState() {
        return new State(this);
    }

    public void shutdown() {
        this.executors.values().forEach(Executor::dispose);
    }

    /**
     * Intermediate state of an interrupted execution of an {@link ExecutionPlan}.
     */
    public static class State {

        private final ExecutionState profile = new ExecutionState();

        private final Set<ExecutionStage> completedStages = new HashSet<>(), suspendedStages = new HashSet<>();

        public State(CrossPlatformExecutor crossPlatformExecutor) {
            this.profile.merge(crossPlatformExecutor.executionState);
            this.completedStages.addAll(crossPlatformExecutor.completedStages);
            this.suspendedStages.addAll(crossPlatformExecutor.suspendedStages);
        }

        public ExecutionState getProfile() {
            return this.profile;
        }

        public Set<ExecutionStage> getCompletedStages() {
            return this.completedStages;
        }

        public Set<ExecutionStage> getSuspendedStages() {
            return this.suspendedStages;
        }

        public boolean isComplete() {
            return this.suspendedStages.isEmpty();
        }
    }


}