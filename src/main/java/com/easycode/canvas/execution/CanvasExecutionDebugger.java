package com.easycode.canvas.execution;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Controls node-boundary breakpoints and stepping for one synchronous execution. */
public final class CanvasExecutionDebugger {
    private static final long CANCELLATION_POLL_MILLIS = 50L;

    private final Set<UUID> breakpoints = new HashSet<>();
    private final Set<UUID> encounteredBreakpoints = new HashSet<>();
    private boolean pauseRequested;
    private boolean paused;
    private boolean singleStep;
    private boolean executionActive;
    private UUID pausedNodeId;
    private Map<UUID, Object> pausedNodeInputs = Map.of();
    private CanvasExecutionPauseReason nextPauseReason;
    private CanvasExecutionPauseReason pausedReason;

    public synchronized void addBreakpoint(UUID nodeId) {
        breakpoints.add(Objects.requireNonNull(nodeId, "nodeId"));
    }

    public synchronized void removeBreakpoint(UUID nodeId) {
        breakpoints.remove(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** Returns the configured breakpoint node IDs. */
    public synchronized Set<UUID> breakpoints() {
        return Set.copyOf(breakpoints);
    }

    /** Returns breakpoint node IDs encountered during the current execution. */
    public synchronized Set<UUID> hitBreakpoints() {
        return Set.copyOf(encounteredBreakpoints);
    }

    /** Returns whether a configured breakpoint has been encountered this run. */
    public synchronized boolean hasHitBreakpoint(UUID nodeId) {
        return encounteredBreakpoints.contains(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** Removes all configured breakpoints. */
    public synchronized void clearBreakpoints() {
        breakpoints.clear();
    }

    /** Pauses before the next node starts. A running node is allowed to finish. */
    public synchronized void pause() {
        pauseRequested = true;
        nextPauseReason = CanvasExecutionPauseReason.REQUESTED;
    }

    /** Continues execution until the next breakpoint or pause request. */
    public synchronized void resume() {
        pauseRequested = false;
        singleStep = false;
        paused = false;
        pausedNodeId = null;
        pausedNodeInputs = Map.of();
        nextPauseReason = null;
        pausedReason = null;
        notifyAll();
    }

    /** Executes one node from the current pause, then pauses at the next node boundary. */
    public synchronized void step() {
        if (!paused) {
            throw new IllegalStateException("execution must be paused before stepping");
        }
        pauseRequested = false;
        singleStep = true;
        paused = false;
        pausedNodeId = null;
        pausedNodeInputs = Map.of();
        nextPauseReason = null;
        pausedReason = null;
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    /** Returns whether execution will pause at the next node boundary. */
    public synchronized boolean isPauseRequested() {
        return pauseRequested;
    }

    public synchronized UUID pausedNodeId() {
        return pausedNodeId;
    }

    /** Returns an immutable snapshot of inputs at the current node-boundary pause. */
    public synchronized Optional<Map<UUID, Object>> pausedNodeInputs() {
        return pausedNodeId == null ? Optional.empty() : Optional.of(pausedNodeInputs);
    }

    /** Returns why the debugger is currently paused, if it is paused. */
    public synchronized Optional<CanvasExecutionPauseReason> pauseReason() {
        return Optional.ofNullable(pausedReason);
    }

    /** Clears per-execution pause state while retaining configured breakpoints. */
    synchronized void complete() {
        pauseRequested = false;
        paused = false;
        singleStep = false;
        executionActive = false;
        pausedNodeId = null;
        pausedNodeInputs = Map.of();
        nextPauseReason = null;
        pausedReason = null;
        encounteredBreakpoints.clear();
        notifyAll();
    }

    synchronized void beginExecution() {
        if (executionActive) {
            throw new IllegalStateException("debugger is already attached to an active execution");
        }
        executionActive = true;
    }

    synchronized void validateBreakpoints(List<UUID> plannedNodeIds) {
        Set<UUID> planned = Set.copyOf(plannedNodeIds);
        if (!planned.containsAll(breakpoints)) {
            Set<UUID> unknown = new HashSet<>(breakpoints);
            unknown.removeAll(planned);
            throw new IllegalArgumentException("breakpoints reference nodes outside this execution: " + unknown);
        }
    }

    /** Waits until execution reaches a pause or the timeout expires. */
    public synchronized boolean awaitPaused(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout cannot be negative");
        }
        long remaining = timeout.toNanos();
        long deadline = System.nanoTime() + remaining;
        while (!paused && remaining > 0) {
            TimeUnit.NANOSECONDS.timedWait(this, remaining);
            remaining = deadline - System.nanoTime();
        }
        return paused;
    }

    synchronized long beforeNode(UUID nodeId, Map<UUID, Object> inputs,
                                 CanvasExecutionCancellationToken cancellationToken,
                                 CanvasExecutionObserver observer, UUID executionId,
                                 long eventSequence) {
        if (breakpoints.contains(nodeId) && encounteredBreakpoints.add(nodeId)) {
            pauseRequested = true;
            nextPauseReason = CanvasExecutionPauseReason.BREAKPOINT;
        }
        if (pauseRequested && !cancellationToken.isCancellationRequested()) {
            paused = true;
            pausedNodeId = nodeId;
            pausedNodeInputs = CanvasExecutionValueSnapshots.snapshot(inputs, "inputs");
            pausedReason = nextPauseReason == null
                    ? CanvasExecutionPauseReason.REQUESTED
                    : nextPauseReason;
            notifyAll();
            eventSequence = emit(observer, executionId, CanvasExecutionEventType.DEBUGGER_PAUSED,
                    nodeId, inputs, eventSequence, pausedReason);
            while (pauseRequested && !cancellationToken.isCancellationRequested()) {
                try {
                    wait(CANCELLATION_POLL_MILLIS);
                } catch (InterruptedException exception) {
                    cancellationToken.cancel();
                    Thread.currentThread().interrupt();
                }
            }
            boolean cancelled = cancellationToken.isCancellationRequested();
            paused = false;
            pausedNodeId = null;
            pausedNodeInputs = Map.of();
            pausedReason = null;
            if (!cancelled) {
                eventSequence = emit(observer, executionId, CanvasExecutionEventType.DEBUGGER_RESUMED,
                        nodeId, eventSequence);
            }
        }
        paused = false;
        pausedNodeId = null;
        pausedNodeInputs = Map.of();
        pausedReason = null;
        return eventSequence;
    }

    synchronized void afterNode() {
        if (singleStep) {
            singleStep = false;
            pauseRequested = true;
            nextPauseReason = CanvasExecutionPauseReason.STEP;
        }
    }

    private static long emit(CanvasExecutionObserver observer, UUID executionId,
                             CanvasExecutionEventType type, UUID nodeId, long sequence) {
        return emit(observer, executionId, type, nodeId, Map.of(), sequence);
    }

    private static long emit(CanvasExecutionObserver observer, UUID executionId,
                             CanvasExecutionEventType type, UUID nodeId,
                             Map<UUID, Object> inputs, long sequence) {
        return emit(observer, executionId, type, nodeId, inputs, sequence, null);
    }

    private static long emit(CanvasExecutionObserver observer, UUID executionId,
                             CanvasExecutionEventType type, UUID nodeId,
                             Map<UUID, Object> inputs, long sequence,
                             CanvasExecutionPauseReason pauseReason) {
        observer.onEvent(new CanvasExecutionEvent(executionId, type, nodeId, "", sequence,
                inputs, Map.of(), List.of(), Instant.now(), null, pauseReason));
        return sequence + 1;
    }
}
