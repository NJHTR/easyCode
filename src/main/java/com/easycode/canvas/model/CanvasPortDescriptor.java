package com.easycode.canvas.model;

import java.util.Objects;

/** Immutable description of one port in a node type definition. */
public record CanvasPortDescriptor(String name, CanvasPortDirection direction) {
    public CanvasPortDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("port name must not be blank");
        }
        Objects.requireNonNull(direction, "direction");
    }
}
