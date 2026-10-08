package com.easycode.canvas.model;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Public, executable-neutral description from which a canvas node can be created. */
public record CanvasNodeDescriptor(
        String nodeType,
        String displayName,
        String description,
        List<CanvasPortDescriptor> ports) {
    public CanvasNodeDescriptor {
        if (nodeType == null || nodeType.isBlank()) {
            throw new IllegalArgumentException("node type must not be blank");
        }
        if (displayName == null || displayName.isBlank()) {
            throw new IllegalArgumentException("display name must not be blank");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        ports = ports == null ? List.of() : List.copyOf(ports);
        if (ports.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("ports cannot contain null");
        }
        Set<String> names = new HashSet<>();
        for (CanvasPortDescriptor port : ports) {
            if (!names.add(port.name())) {
                throw new IllegalArgumentException("duplicate port name: " + port.name());
            }
        }
    }

    /** Creates a node with no configuration values. */
    public CanvasNode create(String name) {
        return create(name, Map.of());
    }

    /** Creates a node with a generated identity and generated identities for its ports. */
    public CanvasNode create(String name, Map<String, Object> configuration) {
        List<CanvasPort> nodePorts = ports.stream()
                .map(port -> new CanvasPort(UUID.randomUUID(), port.name(), port.direction()))
                .toList();
        return new CanvasNode(UUID.randomUUID(), name, nodeType, configuration, nodePorts);
    }
}
