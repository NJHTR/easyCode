package com.easycode.canvas.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable graph document containing nodes, connections, and optional public port bindings. */
public record CanvasDefinition(
        UUID canvasId,
        String name,
        List<CanvasNode> nodes,
        List<CanvasConnection> connections,
        Map<String, UUID> inputBindings,
        Map<String, UUID> outputBindings) {
    public CanvasDefinition(UUID canvasId,
                            String name,
                            List<CanvasNode> nodes,
                            List<CanvasConnection> connections) {
        this(canvasId, name, nodes, connections, Map.of(), Map.of());
    }

    public CanvasDefinition {
        Objects.requireNonNull(canvasId, "canvasId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("canvas name must not be blank");
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        connections = connections == null ? List.of() : List.copyOf(connections);
        if (nodes.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("nodes cannot contain null");
        }
        if (connections.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("connections cannot contain null");
        }
        inputBindings = immutableBindings(inputBindings, "input");
        outputBindings = immutableBindings(outputBindings, "output");
    }

    public static CanvasDefinition empty(UUID canvasId, String name) {
        return new CanvasDefinition(canvasId, name, List.of(), List.of());
    }

    private static Map<String, UUID> immutableBindings(Map<String, UUID> bindings, String kind) {
        if (bindings == null || bindings.isEmpty()) {
            return Map.of();
        }
        Map<String, UUID> copy = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> entry : bindings.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isBlank()) {
                throw new IllegalArgumentException(kind + " binding name must not be blank");
            }
            UUID previous = copy.put(entry.getKey(), Objects.requireNonNull(entry.getValue(),
                    kind + " binding port id"));
            if (previous != null) {
                throw new IllegalArgumentException("duplicate " + kind + " binding name: " + entry.getKey());
            }
        }
        if (copy.values().stream().distinct().count() != copy.size()) {
            throw new IllegalArgumentException(kind + " bindings cannot expose one port more than once");
        }
        return Collections.unmodifiableMap(copy);
    }
}
