package com.easycode.canvas.model;

import java.util.Objects;
import java.util.UUID;

/** Directed connection from an output port to an input port. */
public record CanvasConnection(
        UUID connectionId,
        UUID fromNodeId,
        UUID fromPortId,
        UUID toNodeId,
        UUID toPortId) {
    public CanvasConnection {
        Objects.requireNonNull(connectionId, "connectionId");
        Objects.requireNonNull(fromNodeId, "fromNodeId");
        Objects.requireNonNull(fromPortId, "fromPortId");
        Objects.requireNonNull(toNodeId, "toNodeId");
        Objects.requireNonNull(toPortId, "toPortId");
        if (fromNodeId.equals(toNodeId)) {
            throw new IllegalArgumentException("a connection cannot target the same node");
        }
    }
}
