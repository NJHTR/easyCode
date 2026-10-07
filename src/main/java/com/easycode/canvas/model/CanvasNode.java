package com.easycode.canvas.model;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Immutable executable node declaration inside a canvas. */
public record CanvasNode(
        UUID nodeId,
        String name,
        String nodeType,
        Map<String, Object> configuration,
        List<CanvasPort> ports) {
    public CanvasNode {
        Objects.requireNonNull(nodeId, "nodeId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("node name must not be blank");
        }
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("node type must not be blank");
        }
        configuration = configuration == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(configuration));
        if (configuration.entrySet().stream().anyMatch(entry -> entry.getKey() == null)) {
            throw new IllegalArgumentException("configuration keys cannot be null");
        }
        ports = ports == null ? List.of() : List.copyOf(ports);
        if (ports.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ports cannot contain null");
        }
    }
}
