package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Describes one synchronous canvas run, optional entries, and initial port values. */
public record CanvasExecutionRequest(CanvasDefinition canvas, List<UUID> entryNodeIds,
                                     Map<UUID, Object> initialInputs) {
    public CanvasExecutionRequest(CanvasDefinition canvas, List<UUID> entryNodeIds) {
        this(canvas, entryNodeIds, Map.of());
    }

    public CanvasExecutionRequest {
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
        return new CanvasExecutionRequest(canvas, List.of());
    }

    public static CanvasExecutionRequest fromEntries(CanvasDefinition canvas, List<UUID> entryNodeIds) {
        return new CanvasExecutionRequest(canvas, entryNodeIds);
    }

    public static CanvasExecutionRequest withInputs(CanvasDefinition canvas,
                                                    List<UUID> entryNodeIds,
                                                    Map<UUID, Object> initialInputs) {
        return new CanvasExecutionRequest(canvas, entryNodeIds, initialInputs);
    }

    public static CanvasExecutionRequest withNamedInputs(CanvasDefinition canvas,
                                                         List<UUID> entryNodeIds,
                                                         Map<String, Object> namedInputs) {
        Objects.requireNonNull(canvas, "canvas");
        if (namedInputs == null || namedInputs.isEmpty()) {
            return new CanvasExecutionRequest(canvas, entryNodeIds);
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
        return new CanvasExecutionRequest(canvas, entryNodeIds, values);
    }

    public static CanvasExecutionRequest withNamedInputs(CanvasDefinition canvas,
                                                         Map<String, Object> namedInputs) {
        return withNamedInputs(canvas, List.of(), namedInputs);
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
