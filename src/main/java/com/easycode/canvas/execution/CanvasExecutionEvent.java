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
        Instant occurredAt,
        CanvasExecutionFailure failureDetails,
        CanvasExecutionPauseReason pauseReason) {
    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message,
                                long sequence,
                                Map<UUID, Object> inputs,
                                Map<UUID, Object> outputs,
                                List<String> consoleOutput,
                                Instant occurredAt,
                                CanvasExecutionFailure failureDetails) {
        this(executionId, type, nodeId, message, sequence, inputs, outputs, consoleOutput,
                occurredAt, failureDetails, null);
    }

    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message) {
        this(executionId, type, nodeId, message, 0L, Map.of(), Map.of(), List.of(), Instant.now(), null);
    }

    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message,
                                long sequence) {
        this(executionId, type, nodeId, message, sequence, Map.of(), Map.of(), List.of(), Instant.now(), null);
    }

    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message,
                                long sequence,
                                Map<UUID, Object> inputs,
                                Map<UUID, Object> outputs,
                                List<String> consoleOutput) {
        this(executionId, type, nodeId, message, sequence, inputs, outputs, consoleOutput, Instant.now(), null);
    }

    public CanvasExecutionEvent(UUID executionId,
                                CanvasExecutionEventType type,
                                UUID nodeId,
                                String message,
                                long sequence,
                                Map<UUID, Object> inputs,
                                Map<UUID, Object> outputs,
                                List<String> consoleOutput,
                                Instant occurredAt) {
        this(executionId, type, nodeId, message, sequence, inputs, outputs, consoleOutput, occurredAt, null);
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
                || type == CanvasExecutionEventType.NODE_CONSOLE_OUTPUT
                || type == CanvasExecutionEventType.NODE_SUCCEEDED
                || type == CanvasExecutionEventType.NODE_FAILED
                || type == CanvasExecutionEventType.NODE_CANCELLED
                || type == CanvasExecutionEventType.DEBUGGER_PAUSED
                || type == CanvasExecutionEventType.DEBUGGER_RESUMED;
        if (nodeEvent != (nodeId != null)) {
            throw new IllegalArgumentException("node lifecycle events require a node id");
        }
        if (type == CanvasExecutionEventType.NODE_FAILED || type == CanvasExecutionEventType.FAILED
                || type == CanvasExecutionEventType.NODE_CANCELLED || type == CanvasExecutionEventType.CANCELLED) {
            if (message.isBlank()) {
                throw new IllegalArgumentException("failure and cancellation events require a message");
            }
        } else if (!message.isBlank()) {
            throw new IllegalArgumentException("non-terminal lifecycle events cannot carry a message");
        }
        if (failureDetails != null && type != CanvasExecutionEventType.NODE_FAILED) {
            throw new IllegalArgumentException("failure details can only be attached to node failure events");
        }
        if (pauseReason != null && type != CanvasExecutionEventType.DEBUGGER_PAUSED) {
            throw new IllegalArgumentException("pause reason can only be attached to debugger pause events");
        }
        if (!nodeEvent && (!inputs.isEmpty() || !outputs.isEmpty() || !consoleOutput.isEmpty())) {
            throw new IllegalArgumentException("execution events cannot carry node snapshots");
        }
        if (type == CanvasExecutionEventType.NODE_STARTED
                && (!outputs.isEmpty() || !consoleOutput.isEmpty())) {
            throw new IllegalArgumentException("node started events cannot carry outputs");
        }
        if (type == CanvasExecutionEventType.NODE_CONSOLE_OUTPUT
                && (consoleOutput.size() != 1 || !inputs.isEmpty() || !outputs.isEmpty())) {
            throw new IllegalArgumentException("node console events must carry exactly one line and no port values");
        }
        if ((type == CanvasExecutionEventType.DEBUGGER_PAUSED
                || type == CanvasExecutionEventType.DEBUGGER_RESUMED)
                && (!outputs.isEmpty() || !consoleOutput.isEmpty())) {
            throw new IllegalArgumentException("debugger transition events cannot carry outputs or console lines");
        }
    }

    CanvasExecutionEvent withValueSnapshotter(CanvasExecutionValueSnapshotter snapshotter) {
        Objects.requireNonNull(snapshotter, "snapshotter");
        return new CanvasExecutionEvent(executionId, type, nodeId, message, sequence,
                CanvasExecutionValueSnapshots.snapshot(inputs, "inputs", snapshotter),
                CanvasExecutionValueSnapshots.snapshot(outputs, "outputs", snapshotter),
                consoleOutput, occurredAt, failureDetails, pauseReason);
    }
}
