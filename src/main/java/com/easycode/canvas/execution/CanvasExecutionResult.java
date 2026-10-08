package com.easycode.canvas.execution;

import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable summary of one synchronous canvas run. */
public record CanvasExecutionResult(
        UUID canvasId,
        CanvasExecutionStatus status,
        List<UUID> completedNodeIds,
        List<CanvasNodeExecutionTrace> nodeTraces,
        List<String> consoleOutput,
        Map<UUID, Object> outputValues,
        UUID failedNodeId,
        String failureMessage,
        Duration duration) {
    public CanvasExecutionResult(UUID canvasId,
                                 CanvasExecutionStatus status,
                                 List<UUID> completedNodeIds,
                                 List<CanvasNodeExecutionTrace> nodeTraces,
                                 List<String> consoleOutput,
                                 UUID failedNodeId,
                                 String failureMessage,
                                 Duration duration) {
        this(canvasId, status, completedNodeIds, nodeTraces, consoleOutput, Map.of(),
                failedNodeId, failureMessage, duration);
    }

    public CanvasExecutionResult {
        Objects.requireNonNull(canvasId, "canvasId");
        Objects.requireNonNull(status, "status");
        completedNodeIds = completedNodeIds == null ? List.of() : List.copyOf(completedNodeIds);
        if (completedNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("completed node ids cannot contain null");
        }
        nodeTraces = nodeTraces == null ? List.of() : List.copyOf(nodeTraces);
        if (nodeTraces.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("node traces cannot contain null");
        }
        consoleOutput = consoleOutput == null ? List.of() : List.copyOf(consoleOutput);
        if (consoleOutput.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("console output cannot contain null");
        }
        outputValues = immutableValues(outputValues);
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
        failureMessage = failureMessage == null ? "" : failureMessage;
        if (status == CanvasExecutionStatus.SUCCEEDED && failedNodeId != null) {
            throw new IllegalArgumentException("successful execution cannot have a failed node");
        }
        if (status == CanvasExecutionStatus.SUCCEEDED && !failureMessage.isBlank()) {
            throw new IllegalArgumentException("successful execution cannot have a failure message");
        }
        if (status == CanvasExecutionStatus.FAILED
                && (failedNodeId == null || failureMessage.isBlank())) {
            throw new IllegalArgumentException("failed execution must identify a node and failure");
        }
    }

    private static Map<UUID, Object> immutableValues(Map<UUID, Object> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
            throw new IllegalArgumentException("output values cannot contain a null port id");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
