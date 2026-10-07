package com.easycode.canvas.model;

import java.util.Objects;
import java.util.UUID;

/** Immutable node port used by a canvas connection. */
public record CanvasPort(UUID portId, String name, CanvasPortDirection direction) {
    public CanvasPort {
        Objects.requireNonNull(portId, "portId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("port name must not be blank");
        }
        Objects.requireNonNull(direction, "direction");
    }
}
