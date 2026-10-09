package com.easycode.canvas.execution;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable in-process observation of one canvas execution lifecycle point. */
public record CanvasExecutionEvent(
        UUID executionId,
        CanvasExecutionEventType type,
        UUID nodeId,
        String message,
        long sequence,
        Map<UUID, Object> inputs,
        Map<UUID, Object> outputs,
        List<String> consoleOutput,
        Instant occurredAt) {
    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message) {
        this(executionId, type, nodeId, message, 0L, Map.of(), Map.of(), List.of(), Instant.now());
    }

    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message,
                                long sequence) {
        this(executionId, type, nodeId, message, sequence, Map.of(), Map.of(), List.of(), Instant.now());
    }

    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message,
                                long sequence,
                                Map<UUID, Object> inputs,
                                Map<UUID, Object> outputs,
                                List<String> consoleOutput) {
        this(executionId, type, nodeId, message, sequence, inputs, outputs, consoleOutput, Instant.now());
    }

    public CanvasExecutionEvent {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(occurredAt, "occurredAt");
        if (sequence < 0) {
            throw new IllegalArgumentException("event sequence cannot be negative");
        }
        message = message == null ? "" : message;
        inputs = CanvasExecutionValueSnapshots.snapshot(inputs, "inputs");
        outputs = CanvasExecutionValueSnapshots.snapshot(outputs, "outputs");
        consoleOutput = consoleOutput == null ? List.of() : List.copyOf(consoleOutput);
        if (consoleOutput.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("console output cannot contain null");
        }
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
        if (!nodeEvent && (!inputs.isEmpty() || !outputs.isEmpty() || !consoleOutput.isEmpty())) {
            throw new IllegalArgumentException("execution events cannot carry node snapshots");
        }
        if (type == CanvasExecutionEventType.NODE_STARTED
                && (!outputs.isEmpty() || !consoleOutput.isEmpty())) {
            throw new IllegalArgumentException("node started events cannot carry outputs");
        }
    }
}
