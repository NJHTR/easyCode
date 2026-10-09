package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionRequest;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionPreflightResult;
import com.easycode.canvas.execution.CanvasExecutionObserver;
import com.easycode.canvas.execution.CanvasExecutionCancellationToken;
import com.easycode.canvas.execution.builtin.CanvasBuiltinLibrary;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Small application composition facade for node creation, canvas assembly, and execution. */
public final class CanvasApplication {
    private final CanvasNodeRegistry nodeRegistry;
    private final CanvasService service;

    public CanvasApplication() {
        this(CanvasBuiltinLibrary.registry());
    }

    public CanvasApplication(CanvasNodeRegistry nodeRegistry) {
        this.nodeRegistry = Objects.requireNonNull(nodeRegistry, "nodeRegistry");
        this.service = new CanvasService(nodeRegistry);
    }

    public CanvasNodeRegistry nodes() {
        return nodeRegistry;
    }

    public CanvasNode createNode(String nodeType, String name) {
        return nodeRegistry.create(nodeType, name);
    }

    public CanvasNode createNode(String nodeType, String name, Map<String, Object> configuration) {
        return nodeRegistry.create(nodeType, name, configuration);
    }

    public CanvasBuilder newCanvas(String name) {
        return new CanvasBuilder(name);
    }

    public CanvasBuilder newCanvas(UUID canvasId, String name) {
        return new CanvasBuilder(canvasId, name);
    }

    public CanvasExecutionResult execute(CanvasDefinition canvas) {
        return service.execute(canvas);
    }

    public CanvasExecutionResult execute(CanvasDefinition canvas, Map<String, Object> namedInputs) {
        return service.execute(canvas, namedInputs);
    }

    public CanvasExecutionResult execute(CanvasDefinition canvas,
                                         List<UUID> entryNodeIds,
                                         Map<String, Object> namedInputs) {
        return service.execute(canvas, entryNodeIds, namedInputs);
    }

    public CanvasExecutionResult execute(CanvasExecutionRequest request) {
        return service.execute(request);
    }

    public CanvasExecutionResult execute(CanvasExecutionRequest request,
                                         CanvasExecutionObserver observer) {
        return service.execute(request, observer);
    }

    public CanvasExecutionResult execute(CanvasExecutionRequest request,
                                         CanvasExecutionCancellationToken cancellationToken) {
        return service.execute(request, cancellationToken);
    }

    public CanvasExecutionResult execute(CanvasExecutionRequest request,
                                         CanvasExecutionObserver observer,
                                         CanvasExecutionCancellationToken cancellationToken) {
        return service.execute(request, observer, cancellationToken);
    }

    public CanvasExecutionPreflightResult preflight(CanvasDefinition canvas) {
        return service.preflight(canvas);
    }

    public CanvasExecutionPreflightResult preflight(CanvasDefinition canvas,
                                                     Map<String, Object> namedInputs) {
        return service.preflight(canvas, namedInputs);
    }

    public CanvasExecutionPreflightResult preflight(CanvasDefinition canvas,
                                                     List<UUID> entryNodeIds,
                                                     Map<String, Object> namedInputs) {
        return service.preflight(canvas, entryNodeIds, namedInputs);
    }

    public CanvasExecutionPreflightResult preflight(CanvasExecutionRequest request) {
        return service.preflight(request);
    }
}
