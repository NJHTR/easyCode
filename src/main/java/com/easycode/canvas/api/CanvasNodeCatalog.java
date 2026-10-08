package com.easycode.canvas.api;

import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasNodeDescriptor;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable catalog used to discover and create supported canvas node types. */
public final class CanvasNodeCatalog {
    private final Map<String, CanvasNodeDescriptor> descriptors;

    public CanvasNodeCatalog(Collection<CanvasNodeDescriptor> descriptors) {
        Objects.requireNonNull(descriptors, "descriptors");
        Map<String, CanvasNodeDescriptor> copy = new LinkedHashMap<>();
        for (CanvasNodeDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (copy.putIfAbsent(descriptor.nodeType(), descriptor) != null) {
                throw new IllegalArgumentException("node type already registered: " + descriptor.nodeType());
            }
        }
        this.descriptors = Collections.unmodifiableMap(copy);
    }

    public List<CanvasNodeDescriptor> list() {
        return List.copyOf(descriptors.values());
    }

    public Optional<CanvasNodeDescriptor> find(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(descriptors.get(nodeType));
    }

    public CanvasNode create(String nodeType, String name) {
        return create(nodeType, name, Map.of());
    }

    public CanvasNode create(String nodeType, String name, Map<String, Object> configuration) {
        CanvasNodeDescriptor descriptor = find(nodeType)
                .orElseThrow(() -> new IllegalArgumentException("unknown node type: " + nodeType));
        return descriptor.create(name, configuration);
    }
}
