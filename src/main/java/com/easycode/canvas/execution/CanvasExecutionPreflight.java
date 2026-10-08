package com.easycode.canvas.execution;

import com.easycode.canvas.exception.CanvasValidationException;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import com.easycode.canvas.plan.CanvasExecutionPlan;
import com.easycode.canvas.plan.CanvasExecutionPlanner;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Checks a request without invoking any node executor. */
public final class CanvasExecutionPreflight {
    private final CanvasExecutionPlanner planner;
    private final Map<String, CanvasNodeExecutor> executors;

    public CanvasExecutionPreflight(Map<String, CanvasNodeExecutor> executors) {
        this(new CanvasExecutionPlanner(), executors);
    }

    public CanvasExecutionPreflight(CanvasExecutionPlanner planner,
                                    Map<String, CanvasNodeExecutor> executors) {
        this.planner = Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(executors, "executors");
        Map<String, CanvasNodeExecutor> copy = new LinkedHashMap<>();
        executors.forEach((type, executor) -> {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("executor type must not be blank");
            }
            copy.put(type, Objects.requireNonNull(executor, "executor"));
        });
        this.executors = Collections.unmodifiableMap(copy);
    }

    public CanvasExecutionPreflightResult inspect(CanvasExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        CanvasDefinition canvas = request.canvas();
        try {
            CanvasExecutionPlan plan = prepare(request);
            Map<UUID, CanvasNode> nodes = indexNodes(canvas.nodes());
            List<CanvasExecutionDiagnostic> diagnostics = new ArrayList<>();
            for (UUID nodeId : plan.orderedNodeIds()) {
                CanvasNode node = nodes.get(nodeId);
                if (!executors.containsKey(node.nodeType())) {
                    diagnostics.add(new CanvasExecutionDiagnostic(
                            CanvasExecutionDiagnosticCode.MISSING_EXECUTOR,
                            "no executor registered for node type: " + node.nodeType(),
                            node.nodeId(), node.nodeType()));
                }
            }
            return new CanvasExecutionPreflightResult(canvas.canvasId(), plan.orderedNodeIds(), diagnostics);
        } catch (CanvasValidationException exception) {
            return new CanvasExecutionPreflightResult(canvas.canvasId(), List.of(), List.of(
                    new CanvasExecutionDiagnostic(CanvasExecutionDiagnosticCode.INVALID_GRAPH,
                            exception.getMessage(), null, null)));
        }
    }

    CanvasExecutionPlan prepare(CanvasExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        CanvasExecutionPlan plan = planner.plan(request.canvas(), Set.copyOf(request.entryNodeIds()));
        validateInitialInputs(request.canvas(), plan, request.initialInputs());
        return plan;
    }

    private static Map<UUID, CanvasNode> indexNodes(List<CanvasNode> nodes) {
        Map<UUID, CanvasNode> indexed = new HashMap<>();
        nodes.forEach(node -> indexed.put(node.nodeId(), node));
        return indexed;
    }

    private static void validateInitialInputs(CanvasDefinition canvas,
                                              CanvasExecutionPlan plan,
                                              Map<UUID, Object> initialInputs) {
        if (initialInputs.isEmpty()) {
            return;
        }
        Map<UUID, CanvasPort> ports = new HashMap<>();
        Map<UUID, UUID> owners = new HashMap<>();
        for (CanvasNode node : canvas.nodes()) {
            for (CanvasPort port : node.ports()) {
                ports.put(port.portId(), port);
                owners.put(port.portId(), node.nodeId());
            }
        }
        Set<UUID> connectedInputs = new java.util.HashSet<>();
        for (CanvasConnection connection : canvas.connections()) {
            connectedInputs.add(connection.toPortId());
        }
        Set<UUID> plannedNodes = Set.copyOf(plan.orderedNodeIds());
        for (UUID portId : initialInputs.keySet()) {
            CanvasPort port = ports.get(portId);
            if (port == null) {
                throw new CanvasValidationException("initial input references an unknown port: " + portId);
            }
            if (port.direction() != CanvasPortDirection.INPUT) {
                throw new CanvasValidationException("initial input must target an input port: " + portId);
            }
            if (connectedInputs.contains(portId)) {
                throw new CanvasValidationException("initial input cannot target a connected port: " + portId);
            }
            if (!plannedNodes.contains(owners.get(portId))) {
                throw new CanvasValidationException("initial input targets a node outside the execution scope: "
                        + owners.get(portId));
            }
        }
    }
}
