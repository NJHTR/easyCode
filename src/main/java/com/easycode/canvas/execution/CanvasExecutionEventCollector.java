package com.easycode.canvas.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Collects lifecycle events from one synchronous execution for later inspection.
 * The collector is intentionally in-memory and does not provide persistence or
 * realtime delivery semantics.
 */
public final class CanvasExecutionEventCollector implements CanvasExecutionObserver {
    private final List<CanvasExecutionEvent> events = new ArrayList<>();
    private final CanvasExecutionValueSnapshotter snapshotter;
    private UUID executionId;
    private boolean terminal;
    private UUID activeNodeId;
    private UUID debuggerPausedNodeId;
    private CanvasExecutionEventType lastNodeOutcome;
    private final List<UUID> completedNodeIds = new ArrayList<>();
    private CanvasExecutionStatus terminalStatus;

    public CanvasExecutionEventCollector() {
        this(CanvasExecutionValueSnapshotter.identity());
    }

    public CanvasExecutionEventCollector(CanvasExecutionValueSnapshotter snapshotter) {
        this.snapshotter = Objects.requireNonNull(snapshotter, "snapshotter");
    }

    @Override
    public synchronized void onEvent(CanvasExecutionEvent event) {
        Objects.requireNonNull(event, "event");
        if (terminal) {
            throw new IllegalStateException("execution event collector already received a terminal event");
        }
        if (events.isEmpty()) {
            if (event.type() != CanvasExecutionEventType.STARTED || event.sequence() != 0L) {
                throw new IllegalArgumentException("first execution event must be STARTED with sequence 0");
            }
            executionId = event.executionId();
        } else {
            if (!executionId.equals(event.executionId())) {
                throw new IllegalArgumentException("execution event belongs to a different execution");
            }
            if (event.sequence() != events.size()) {
                throw new IllegalArgumentException("execution event sequence is not contiguous");
            }
        }
        CanvasExecutionEvent snapshot = event.withValueSnapshotter(snapshotter);
        validateLifecycle(event);
        if (event.type() == CanvasExecutionEventType.DEBUGGER_PAUSED) {
            debuggerPausedNodeId = event.nodeId();
        } else if (event.type() == CanvasExecutionEventType.DEBUGGER_RESUMED) {
            debuggerPausedNodeId = null;
        }
        if (event.type() == CanvasExecutionEventType.NODE_STARTED) {
            activeNodeId = event.nodeId();
        } else if (event.type() == CanvasExecutionEventType.NODE_SUCCEEDED
                || event.type() == CanvasExecutionEventType.NODE_FAILED
                || event.type() == CanvasExecutionEventType.NODE_CANCELLED) {
            activeNodeId = null;
            lastNodeOutcome = event.type();
            if (event.type() == CanvasExecutionEventType.NODE_SUCCEEDED) {
                completedNodeIds.add(event.nodeId());
            }
        }
        events.add(snapshot);
        terminalStatus = terminalStatusOf(event.type());
        terminal = terminalStatus != null;
    }

    private void validateLifecycle(CanvasExecutionEvent event) {
        CanvasExecutionEventType type = event.type();
        if (events.isEmpty()) {
            return;
        }
        if ((lastNodeOutcome == CanvasExecutionEventType.NODE_FAILED && type != CanvasExecutionEventType.FAILED)
                || (lastNodeOutcome == CanvasExecutionEventType.NODE_CANCELLED
                && type != CanvasExecutionEventType.CANCELLED)) {
            throw new IllegalArgumentException("terminal node outcome must be followed by its execution outcome");
        }
        switch (type) {
            case STARTED -> throw new IllegalArgumentException("execution can only start once");
            case DEBUGGER_PAUSED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null) {
                    throw new IllegalArgumentException("debugger can pause only between nodes");
                }
            }
            case DEBUGGER_RESUMED -> {
                if (debuggerPausedNodeId == null || !event.nodeId().equals(debuggerPausedNodeId)) {
                    throw new IllegalArgumentException("debugger resume must match the paused node");
                }
            }
            case NODE_STARTED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null) {
                    throw new IllegalArgumentException("node execution cannot overlap another node or debugger pause");
                }
            }
            case NODE_SUCCEEDED, NODE_FAILED, NODE_CANCELLED -> {
                if (!event.nodeId().equals(activeNodeId)) {
                    throw new IllegalArgumentException("node outcome must match the active node");
                }
            }
            case SUCCEEDED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null
                        || lastNodeOutcome == CanvasExecutionEventType.NODE_FAILED
                        || lastNodeOutcome == CanvasExecutionEventType.NODE_CANCELLED) {
                    throw new IllegalArgumentException("successful terminal event conflicts with execution state");
                }
            }
            case FAILED -> {
                if (activeNodeId != null || debuggerPausedNodeId != null
                        || lastNodeOutcome != CanvasExecutionEventType.NODE_FAILED) {
                    throw new IllegalArgumentException("failed terminal event requires a failed node outcome");
                }
            }
            case CANCELLED -> {
                if (activeNodeId != null || lastNodeOutcome == CanvasExecutionEventType.NODE_FAILED) {
                    throw new IllegalArgumentException("cancelled terminal event conflicts with execution state");
                }
            }
        }
    }

    /** Returns an immutable snapshot of all events collected so far. */
    public synchronized List<CanvasExecutionEvent> events() {
        return List.copyOf(events);
    }

    /** Returns lifecycle events for one node in their original execution order. */
    public synchronized List<CanvasExecutionEvent> eventsForNode(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return events.stream()
                .filter(event -> nodeId.equals(event.nodeId()))
                .toList();
    }

    /** Returns events of one lifecycle type in their original execution order. */
    public synchronized List<CanvasExecutionEvent> eventsOfType(CanvasExecutionEventType type) {
        Objects.requireNonNull(type, "type");
        return events.stream()
                .filter(event -> event.type() == type)
                .toList();
    }

    /** Returns the number of events collected so far. */
    public synchronized int size() {
        return events.size();
    }

    /** Returns whether a terminal success, failure, or cancellation event has been collected. */
    public synchronized boolean isComplete() {
        return terminal;
    }

    /** Returns the node whose executor is currently running, if any. */
    public synchronized Optional<UUID> activeNodeId() {
        return Optional.ofNullable(activeNodeId);
    }

    /** Returns the node at which execution is currently paused, if any. */
    public synchronized Optional<UUID> pausedNodeId() {
        return Optional.ofNullable(debuggerPausedNodeId);
    }

    /** Returns node IDs that have completed successfully in execution order. */
    public synchronized List<UUID> completedNodeIds() {
        return List.copyOf(completedNodeIds);
    }

    /** Returns the terminal status once execution has completed. */
    public synchronized Optional<CanvasExecutionStatus> terminalStatus() {
        return Optional.ofNullable(terminalStatus);
    }

    private static CanvasExecutionStatus terminalStatusOf(CanvasExecutionEventType type) {
        return switch (type) {
            case SUCCEEDED -> CanvasExecutionStatus.SUCCEEDED;
            case FAILED -> CanvasExecutionStatus.FAILED;
            case CANCELLED -> CanvasExecutionStatus.CANCELLED;
            default -> null;
        };
    }
}
