package com.easycode.canvas.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Collects lifecycle events from one synchronous execution for later inspection.
 * The collector is intentionally in-memory and does not provide persistence or
 * realtime delivery semantics.
 */
public final class CanvasExecutionEventCollector implements CanvasExecutionObserver {
    private final List<CanvasExecutionEvent> events = new ArrayList<>();
    private UUID executionId;
    private boolean terminal;

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
        events.add(event);
        terminal = event.type() == CanvasExecutionEventType.SUCCEEDED
                || event.type() == CanvasExecutionEventType.FAILED;
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

    /** Returns whether a terminal success or failure event has been collected. */
    public synchronized boolean isComplete() {
        return terminal;
    }
}
