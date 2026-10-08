package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Describes one synchronous canvas run, optional entries, and initial port values. */
public record CanvasExecutionRequest(UUID executionId, CanvasDefinition canvas, List<UUID> entryNodeIds,
                                     Map<UUID, Object> initialInputs) {
    public CanvasExecutionRequest(CanvasDefinition canvas, List<UUID> entryNodeIds) {
        this(UUID.randomUUID(), canvas, entryNodeIds, Map.of());
    }

    public CanvasExecutionRequest(CanvasDefinition canvas, List<UUID> entryNodeIds,
                                  Map<UUID, Object> initialInputs) {
        this(UUID.randomUUID(), canvas, entryNodeIds, initialInputs);
    }

    public CanvasExecutionRequest {
        Objects.requireNonNull(executionId, "executionId");
        Objects.requireNonNull(canvas, "canvas");
        entryNodeIds = entryNodeIds == null ? List.of() : List.copyOf(entryNodeIds);
        if (entryNodeIds.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("entry node ids cannot contain null");
        }
        if (entryNodeIds.stream().distinct().count() != entryNodeIds.size()) {
            throw new IllegalArgumentException("entry node ids cannot contain duplicates");
        }
        initialInputs = immutableValues(initialInputs);
    }

    public static CanvasExecutionRequest forCanvas(CanvasDefinition canvas) {
        return forCanvas(UUID.randomUUID(), canvas);
    }

    public static CanvasExecutionRequest forCanvas(UUID executionId, CanvasDefinition canvas) {
        return new CanvasExecutionRequest(executionId, canvas, List.of(), Map.of());
    }

    public static CanvasExecutionRequest fromEntries(CanvasDefinition canvas, List<UUID> entryNodeIds) {
        return fromEntries(UUID.randomUUID(), canvas, entryNodeIds);
    }

    public static CanvasExecutionRequest fromEntries(UUID executionId, CanvasDefinition canvas,
                                                     List<UUID> entryNodeIds) {
        return new CanvasExecutionRequest(executionId, canvas, entryNodeIds, Map.of());
    }

    public static CanvasExecutionRequest withInputs(CanvasDefinition canvas,
                                                    List<UUID> entryNodeIds,
                                                    Map<UUID, Object> initialInputs) {
        return withInputs(UUID.randomUUID(), canvas, entryNodeIds, initialInputs);
    }

    public static CanvasExecutionRequest withInputs(UUID executionId, CanvasDefinition canvas,
                                                    List<UUID> entryNodeIds,
                                                    Map<UUID, Object> initialInputs) {
        return new CanvasExecutionRequest(executionId, canvas, entryNodeIds, initialInputs);
    }

    public static CanvasExecutionRequest withNamedInputs(CanvasDefinition canvas,
                                                         List<UUID> entryNodeIds,
                                                         Map<String, Object> namedInputs) {
        return withNamedInputs(UUID.randomUUID(), canvas, entryNodeIds, namedInputs);
    }

    public static CanvasExecutionRequest withNamedInputs(UUID executionId,
                                                         CanvasDefinition canvas,
                                                         List<UUID> entryNodeIds,
                                                         Map<String, Object> namedInputs) {
        Objects.requireNonNull(canvas, "canvas");
        if (namedInputs == null || namedInputs.isEmpty()) {
            return new CanvasExecutionRequest(executionId, canvas, entryNodeIds, Map.of());
        }
        Map<UUID, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, Object> input : namedInputs.entrySet()) {
            if (input.getKey() == null || input.getKey().isBlank()) {
                throw new IllegalArgumentException("named input must not be blank");
            }
            UUID portId = canvas.inputBindings().get(input.getKey());
            if (portId == null) {
                throw new IllegalArgumentException("unknown canvas input: " + input.getKey());
            }
            values.put(portId, input.getValue());
        }
        return new CanvasExecutionRequest(executionId, canvas, entryNodeIds, values);
    }

    public static CanvasExecutionRequest withNamedInputs(CanvasDefinition canvas,
                                                         Map<String, Object> namedInputs) {
        return withNamedInputs(UUID.randomUUID(), canvas, List.of(), namedInputs);
    }

    public static CanvasExecutionRequest withNamedInputs(UUID executionId,
                                                         CanvasDefinition canvas,
                                                         Map<String, Object> namedInputs) {
        return withNamedInputs(executionId, canvas, List.of(), namedInputs);
    }

    private static Map<UUID, Object> immutableValues(Map<UUID, Object> values) {
        if (values == null || values.isEmpty()) {
            return Map.of();
        }
        if (values.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
            throw new IllegalArgumentException("initial input port ids cannot contain null");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
