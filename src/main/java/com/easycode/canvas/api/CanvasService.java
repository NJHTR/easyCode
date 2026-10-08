package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionEngine;
import com.easycode.canvas.execution.CanvasExecutionObserver;
import com.easycode.canvas.execution.CanvasExecutionPreflightResult;
import com.easycode.canvas.execution.CanvasExecutionRequest;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasNodeExecutor;
import com.easycode.canvas.execution.builtin.CanvasBuiltinLibrary;
import com.easycode.canvas.model.CanvasDefinition;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Simple application entry point for synchronous in-memory canvas runs. */
public final class CanvasService {
    private final CanvasExecutionEngine engine;

    /** Creates a service with the small built-in node library. */
    public CanvasService() {
        this(CanvasBuiltinLibrary.registry());
    }

    /** Creates a service from one registry containing both node shape and behavior. */
    public CanvasService(CanvasNodeRegistry registry) {
        this(Objects.requireNonNull(registry, "registry").executors());
    }

    /** Creates a service with the exact executor set supplied by the caller. */
    public CanvasService(Map<String, CanvasNodeExecutor> executors) {
        this(new CanvasExecutionEngine(executors));
    }

    public CanvasService(CanvasExecutionEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    /** Executes the complete canvas using implicit root nodes. */
    public CanvasExecutionResult execute(CanvasDefinition canvas) {
        return engine.execute(canvas);
    }

    /** Executes the complete canvas with values addressed by public input names. */
    public CanvasExecutionResult execute(CanvasDefinition canvas, Map<String, Object> namedInputs) {
        return engine.execute(CanvasExecutionRequest.withNamedInputs(canvas, namedInputs));
    }

    /** Executes selected entry branches with values addressed by public input names. */
    public CanvasExecutionResult execute(CanvasDefinition canvas,
                                         List<UUID> entryNodeIds,
                                         Map<String, Object> namedInputs) {
        return engine.execute(CanvasExecutionRequest.withNamedInputs(canvas, entryNodeIds, namedInputs));
    }

    /** Executes an already prepared request for advanced callers. */
    public CanvasExecutionResult execute(CanvasExecutionRequest request) {
        return engine.execute(request);
    }

    /** Executes a request and observes its synchronous lifecycle events. */
    public CanvasExecutionResult execute(CanvasExecutionRequest request,
                                         CanvasExecutionObserver observer) {
        return engine.execute(request, observer);
    }

    public CanvasExecutionPreflightResult preflight(CanvasDefinition canvas) {
        return preflight(CanvasExecutionRequest.forCanvas(canvas));
    }

    public CanvasExecutionPreflightResult preflight(CanvasDefinition canvas,
                                                     Map<String, Object> namedInputs) {
        UUID executionId = UUID.randomUUID();
        try {
            return preflight(CanvasExecutionRequest.withNamedInputs(executionId, canvas, namedInputs));
        } catch (IllegalArgumentException exception) {
            return CanvasExecutionPreflightResult.invalidRequest(
                    executionId, canvas.canvasId(), exception.getMessage());
        }
    }

    public CanvasExecutionPreflightResult preflight(CanvasDefinition canvas,
                                                     List<UUID> entryNodeIds,
                                                     Map<String, Object> namedInputs) {
        UUID executionId = UUID.randomUUID();
        try {
            return preflight(CanvasExecutionRequest.withNamedInputs(
                    executionId, canvas, entryNodeIds, namedInputs));
        } catch (IllegalArgumentException exception) {
            return CanvasExecutionPreflightResult.invalidRequest(
                    executionId, canvas.canvasId(), exception.getMessage());
        }
    }

    public CanvasExecutionPreflightResult preflight(CanvasExecutionRequest request) {
        return engine.preflight(request);
    }
}
