package com.easycode.canvas.desktop;

import com.easycode.canvas.model.CanvasConnection;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Versioned, local file representation of one editable canvas. */
public record CanvasMvpDocument(
        int formatVersion,
        UUID canvasId,
        String name,
        List<CanvasMvpWorkspace.PlacedNode> nodes,
        List<CanvasConnection> connections) {
    public static final int CURRENT_FORMAT_VERSION = 1;
    public static final int MAX_NODES = 1_000;
    public static final int MAX_CONNECTIONS = 5_000;

    public CanvasMvpDocument {
        if (formatVersion != CURRENT_FORMAT_VERSION) {
            throw new IllegalArgumentException("unsupported canvas file version: " + formatVersion);
        }
        Objects.requireNonNull(canvasId, "canvasId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("canvas name must not be blank");
        }
        nodes = nodes == null ? List.of() : List.copyOf(nodes);
        connections = connections == null ? List.of() : List.copyOf(connections);
        if (nodes.size() > MAX_NODES || connections.size() > MAX_CONNECTIONS) {
            throw new IllegalArgumentException("canvas file exceeds the supported graph size");
        }
        if (nodes.stream().anyMatch(Objects::isNull)
                || connections.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("canvas document collections cannot contain null");
        }
    }
}
