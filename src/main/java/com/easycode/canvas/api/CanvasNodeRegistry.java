package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasNodeExecutor;
import com.easycode.canvas.model.CanvasNode;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable source of truth for discoverable and executable node types. */
public final class CanvasNodeRegistry {
    private final Map<String, CanvasNodeRegistration> registrations;
    private final CanvasNodeCatalog catalog;
    private final Map<String, CanvasNodeExecutor> executors;

    public CanvasNodeRegistry(Collection<CanvasNodeRegistration> registrations) {
        Objects.requireNonNull(registrations, "registrations");
        Map<String, CanvasNodeRegistration> copy = new LinkedHashMap<>();
        for (CanvasNodeRegistration registration : registrations) {
            Objects.requireNonNull(registration, "registration");
            String nodeType = registration.descriptor().nodeType();
            if (copy.putIfAbsent(nodeType, registration) != null) {
                throw new IllegalArgumentException("node type already registered: " + nodeType);
            }
        }
        this.registrations = Collections.unmodifiableMap(copy);
        this.catalog = new CanvasNodeCatalog(copy.values().stream()
                .map(CanvasNodeRegistration::descriptor)
                .toList());
        Map<String, CanvasNodeExecutor> executorMap = new LinkedHashMap<>();
        copy.forEach((nodeType, registration) -> executorMap.put(nodeType, registration.executor()));
        this.executors = Collections.unmodifiableMap(executorMap);
    }

    public List<CanvasNodeRegistration> list() {
        return List.copyOf(registrations.values());
    }

    public Optional<CanvasNodeRegistration> find(String nodeType) {
        if (nodeType == null || nodeType.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registrations.get(nodeType));
    }

    public CanvasNodeCatalog catalog() {
        return catalog;
    }

    public Map<String, CanvasNodeExecutor> executors() {
        return executors;
    }

    public CanvasNode create(String nodeType, String name) {
        return catalog.create(nodeType, name);
    }

    public CanvasNode create(String nodeType, String name, Map<String, Object> configuration) {
        return catalog.create(nodeType, name, configuration);
    }
}
