package com.easycode.canvas.execution;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
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
    private UUID pausedNodeId;

    public synchronized void addBreakpoint(UUID nodeId) {
        breakpoints.add(Objects.requireNonNull(nodeId, "nodeId"));
    }

    public synchronized void removeBreakpoint(UUID nodeId) {
        breakpoints.remove(Objects.requireNonNull(nodeId, "nodeId"));
    }

    /** Pauses before the next node starts. A running node is allowed to finish. */
    public synchronized void pause() {
        pauseRequested = true;
    }

    /** Continues execution until the next breakpoint or pause request. */
    public synchronized void resume() {
        pauseRequested = false;
        singleStep = false;
        paused = false;
        pausedNodeId = null;
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
        notifyAll();
    }

    public synchronized boolean isPaused() {
        return paused;
    }

    public synchronized UUID pausedNodeId() {
        return pausedNodeId;
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

    synchronized boolean beforeNode(UUID nodeId, CanvasExecutionCancellationToken cancellationToken) {
        if (breakpoints.contains(nodeId) && encounteredBreakpoints.add(nodeId)) {
            pauseRequested = true;
        }
        while (pauseRequested && !cancellationToken.isCancellationRequested()) {
            paused = true;
            pausedNodeId = nodeId;
            notifyAll();
            try {
                wait(CANCELLATION_POLL_MILLIS);
            } catch (InterruptedException exception) {
                cancellationToken.cancel();
                Thread.currentThread().interrupt();
            }
        }
        paused = false;
        pausedNodeId = null;
        return !cancellationToken.isCancellationRequested();
    }

    synchronized void afterNode() {
        if (singleStep) {
            singleStep = false;
            pauseRequested = true;
        }
    }
}
