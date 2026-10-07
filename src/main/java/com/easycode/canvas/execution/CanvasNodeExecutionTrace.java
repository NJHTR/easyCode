package com.easycode.canvas.execution;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
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
        String failureMessage) {
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
        if (status == CanvasExecutionStatus.FAILED && failureMessage.isBlank()) {
            throw new IllegalArgumentException("failed trace must contain a failure message");
        }
    }

    private static Map<UUID, Object> immutableValues(Map<UUID, Object> values, String name) {
        if (values == null) {
            return Map.of();
        }
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
            throw new IllegalArgumentException(name + " cannot contain a null port id");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
