package com.easycode.canvas.execution;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Immutable summary of one synchronous canvas run. */
public record CanvasExecutionResult(
        UUID executionId,
        UUID canvasId,
        CanvasExecutionStatus status,
        List<UUID> completedNodeIds,
        List<CanvasNodeExecutionTrace> nodeTraces,
        List<String> consoleOutput,
        Map<UUID, Object> outputValues,
        Map<String, Object> namedOutputValues,
        UUID failedNodeId,
        String failureMessage,
        Duration duration) {
    public CanvasExecutionResult(UUID canvasId,
                                 CanvasExecutionStatus status,
                                 List<UUID> completedNodeIds,
                                 List<CanvasNodeExecutionTrace> nodeTraces,
                                 List<String> consoleOutput,
                                 Map<UUID, Object> outputValues,
                                 Map<String, Object> namedOutputValues,
                                 UUID failedNodeId,
                                 String failureMessage,
                                 Duration duration) {
        this(UUID.randomUUID(), canvasId, status, completedNodeIds, nodeTraces, consoleOutput,
                outputValues, namedOutputValues, failedNodeId, failureMessage, duration);
    }

    public CanvasExecutionResult(UUID canvasId,
                                 CanvasExecutionStatus status,
                                 List<UUID> completedNodeIds,
                                 List<CanvasNodeExecutionTrace> nodeTraces,
                                 List<String> consoleOutput,
                                 UUID failedNodeId,
                                 String failureMessage,
                                 Duration duration) {
        this(canvasId, status, completedNodeIds, nodeTraces, consoleOutput, Map.of(), Map.of(),
                failedNodeId, failureMessage, duration);
    }

    public CanvasExecutionResult(UUID canvasId,
                                 CanvasExecutionStatus status,
                                 List<UUID> completedNodeIds,
                                 List<CanvasNodeExecutionTrace> nodeTraces,
                                 List<String> consoleOutput,
                                 Map<UUID, Object> outputValues,
                                 UUID failedNodeId,
                                 String failureMessage,
                                 Duration duration) {
        this(canvasId, status, completedNodeIds, nodeTraces, consoleOutput, outputValues, Map.of(),
                failedNodeId, failureMessage, duration);
    }

    public CanvasExecutionResult {
        Objects.requireNonNull(executionId, "executionId");
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
        Set<UUID> tracedNodeIds = new HashSet<>();
        List<UUID> successfulNodeIds = new ArrayList<>();
        int failedTraceCount = 0;
        int cancelledTraceCount = 0;
        for (int index = 0; index < nodeTraces.size(); index++) {
            CanvasNodeExecutionTrace trace = nodeTraces.get(index);
            if (!tracedNodeIds.add(trace.nodeId())) {
                throw new IllegalArgumentException("node traces cannot contain duplicate node ids");
            }
            switch (trace.status()) {
                case SUCCEEDED -> successfulNodeIds.add(trace.nodeId());
                case FAILED -> {
                    failedTraceCount++;
                    if (index != nodeTraces.size() - 1) {
                        throw new IllegalArgumentException("failed node trace must be the final node trace");
                    }
                }
                case CANCELLED -> {
                    cancelledTraceCount++;
                    if (index != nodeTraces.size() - 1) {
                        throw new IllegalArgumentException("cancelled node trace must be the final node trace");
                    }
                }
            }
        }
        if (new HashSet<>(completedNodeIds).size() != completedNodeIds.size()) {
            throw new IllegalArgumentException("completed node ids cannot contain duplicates");
        }
        if (!completedNodeIds.equals(successfulNodeIds)) {
            throw new IllegalArgumentException("completed node ids must match successful node traces in order");
        }
        consoleOutput = consoleOutput == null ? List.of() : List.copyOf(consoleOutput);
        if (consoleOutput.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("console output cannot contain null");
        }
        outputValues = immutableValues(outputValues);
        namedOutputValues = immutableNamedValues(namedOutputValues);
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
        if (status == CanvasExecutionStatus.CANCELLED
                && (failedNodeId != null || !failureMessage.isBlank())) {
            throw new IllegalArgumentException("cancelled execution cannot have a failed node or failure message");
        }
        if (status == CanvasExecutionStatus.SUCCEEDED && (failedTraceCount != 0 || cancelledTraceCount != 0)) {
            throw new IllegalArgumentException("successful execution cannot contain failed or cancelled node traces");
        }
        if (status == CanvasExecutionStatus.FAILED
                && (failedTraceCount != 1 || cancelledTraceCount != 0
                || !Objects.equals(nodeTraces.get(nodeTraces.size() - 1).nodeId(), failedNodeId))) {
            throw new IllegalArgumentException("failed execution must end with its failed node trace");
        }
        if (status == CanvasExecutionStatus.CANCELLED && (failedTraceCount != 0 || cancelledTraceCount > 1)) {
            throw new IllegalArgumentException("cancelled execution cannot contain failed traces or multiple cancelled nodes");
        }
    }

    public CanvasValueLookup output(UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return outputValues.containsKey(portId)
                ? CanvasValueLookup.present(outputValues.get(portId))
                : CanvasValueLookup.missing();
    }

    public CanvasValueLookup namedOutput(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("output name must not be blank");
        }
        return namedOutputValues.containsKey(name)
                ? CanvasValueLookup.present(namedOutputValues.get(name))
                : CanvasValueLookup.missing();
    }

    public Optional<CanvasNodeExecutionTrace> trace(UUID nodeId) {
        Objects.requireNonNull(nodeId, "nodeId");
        return nodeTraces.stream().filter(trace -> trace.nodeId().equals(nodeId)).findFirst();
    }

    /** Returns a node observation projected from the immutable post-run trace. */
    public Optional<CanvasNodeExecutionObservation> nodeObservation(UUID nodeId) {
        return trace(nodeId).map(trace -> new CanvasNodeExecutionObservation(
                trace.nodeId(),
                Optional.empty(),
                Optional.empty(),
                Optional.of(trace.duration()),
                trace.inputs(),
                Optional.of(trace.outputs()),
                trace.consoleOutput(),
                Optional.of(trace.status()),
                trace.failureMessage().isBlank()
                        ? Optional.empty()
                        : Optional.of(trace.failureMessage()),
                Optional.ofNullable(trace.failureDetails())));
    }

    /** Returns immutable node observations in the result trace order. */
    public Map<UUID, CanvasNodeExecutionObservation> nodeObservations() {
        Map<UUID, CanvasNodeExecutionObservation> observations = new LinkedHashMap<>();
        for (CanvasNodeExecutionTrace trace : nodeTraces) {
            nodeObservation(trace.nodeId()).ifPresent(observation ->
                    observations.put(trace.nodeId(), observation));
        }
        return Collections.unmodifiableMap(observations);
    }

    /** Returns node ids in the order they appear in the post-run trace. */
    public List<UUID> observedNodeIds() {
        return nodeTraces.stream().map(CanvasNodeExecutionTrace::nodeId).toList();
    }

    /** Returns exception diagnostics for the failed node, when an exception was thrown. */
    public Optional<CanvasExecutionFailure> failureDetails() {
        if (failedNodeId == null) {
            return Optional.empty();
        }
        return trace(failedNodeId)
                .map(CanvasNodeExecutionTrace::failureDetails)
                .filter(Objects::nonNull);
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

    private static Map<String, Object> immutableNamedValues(Map<String, Object> values) {
        if (values == null) {
            return Map.of();
        }
        if (values.keySet().stream().anyMatch(name -> name == null || name.isBlank())) {
            throw new IllegalArgumentException("named output values cannot contain a blank name");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
