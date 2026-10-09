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

    @Override
    public synchronized void onEvent(CanvasExecutionEvent event) {
        events.add(Objects.requireNonNull(event, "event"));
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
}
