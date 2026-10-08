package com.easycode.canvas.execution;

import java.util.Objects;
import java.util.UUID;

/** Immutable in-process observation of one canvas execution lifecycle point. */
public record CanvasExecutionEvent(
        UUID executionId,
        CanvasExecutionEventType type,
        UUID nodeId,
        String message,
        long sequence) {
    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message) {
        this(executionId, type, nodeId, message, 0L);
    }

    public CanvasExecutionEvent {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(type, "type");
        if (sequence < 0) {
            throw new IllegalArgumentException("event sequence cannot be negative");
        }
        message = message == null ? "" : message;
        boolean nodeEvent = type == CanvasExecutionEventType.NODE_STARTED
                || type == CanvasExecutionEventType.NODE_SUCCEEDED
                || type == CanvasExecutionEventType.NODE_FAILED;
        if (nodeEvent != (nodeId != null)) {
            throw new IllegalArgumentException("node lifecycle events require a node id");
        }
        if (type == CanvasExecutionEventType.NODE_FAILED || type == CanvasExecutionEventType.FAILED) {
            if (message.isBlank()) {
                throw new IllegalArgumentException("failure events require a message");
            }
        } else if (!message.isBlank()) {
            throw new IllegalArgumentException("non-failure lifecycle events cannot carry a message");
        }
    }
}
