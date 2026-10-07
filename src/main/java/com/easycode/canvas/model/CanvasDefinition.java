package com.easycode.canvas.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Immutable graph document containing nodes and directed connections. */
public record CanvasDefinition(
        UUID canvasId,
        String name,
        List<CanvasNode> nodes,
        List<CanvasConnection> connections) {
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
    }

    public static CanvasDefinition empty(UUID canvasId, String name) {
        return new CanvasDefinition(canvasId, name, List.of(), List.of());
    }
}
