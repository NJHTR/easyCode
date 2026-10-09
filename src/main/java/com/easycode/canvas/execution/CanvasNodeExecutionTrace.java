package com.easycode.canvas.execution;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable post-run observation of one node invocation. */
public record CanvasNodeExecutionTrace(
        UUID nodeId,
        String nodeType,
        CanvasExecutionStatus status,
        Map<UUID, Object> inputs,
        Map<UUID, Object> outputs,
        java.util.List<String> consoleOutput,
        Duration duration,
        String failureMessage,
        CanvasExecutionFailure failureDetails) {
    public CanvasNodeExecutionTrace(UUID nodeId,
                                    String nodeType,
                                    CanvasExecutionStatus status,
                                    Map<UUID, Object> inputs,
                                    Map<UUID, Object> outputs,
                                    java.util.List<String> consoleOutput,
                                    Duration duration,
                                    String failureMessage) {
        this(nodeId, nodeType, status, inputs, outputs, consoleOutput, duration, failureMessage, null);
    }

    public CanvasNodeExecutionTrace {
        Objects.requireNonNull(nodeId, "nodeId");
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("node type must not be blank");
        }
        Objects.requireNonNull(status, "status");
        inputs = immutableValues(inputs, "inputs");
        outputs = immutableValues(outputs, "outputs");
        consoleOutput = consoleOutput == null ? java.util.List.of() : java.util.List.copyOf(consoleOutput);
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (status == CanvasExecutionStatus.SUCCEEDED && !failureMessage.isBlank()) {
            throw new IllegalArgumentException("successful trace cannot have a failure message");
        }
        if (status == CanvasExecutionStatus.FAILED && failureMessage.isBlank()) {
            throw new IllegalArgumentException("failed trace must contain a failure message");
        }
        if (status == CanvasExecutionStatus.CANCELLED && !failureMessage.isBlank()) {
            throw new IllegalArgumentException("cancelled trace cannot have a failure message");
        }
        if (failureDetails != null
                && (status != CanvasExecutionStatus.FAILED
                || !failureMessage.equals(failureDetails.message()))) {
            throw new IllegalArgumentException("failure details must match a failed trace message");
        }
    }

    public CanvasValueLookup input(UUID portId) {
        return lookup(inputs, portId);
    }

    public CanvasValueLookup output(UUID portId) {
        return lookup(outputs, portId);
    }

    private static CanvasValueLookup lookup(Map<UUID, Object> values, UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return values.containsKey(portId)
                ? CanvasValueLookup.present(values.get(portId))
                : CanvasValueLookup.missing();
    }

    private static Map<UUID, Object> immutableValues(Map<UUID, Object> values, String name) {
        return CanvasExecutionValueSnapshots.snapshot(values, name);
    }
}
