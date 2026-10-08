package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.plan.CanvasExecutionPlan;
import com.easycode.canvas.plan.CanvasExecutionPlanner;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Small synchronous graph runner. It dispatches node types and moves values
 * between connected ports; it does not start processes or schedule work.
 */
public final class CanvasExecutionEngine {
    private final Map<String, CanvasNodeExecutor> executors;
    private final CanvasExecutionPreflight preflight;

    public CanvasExecutionEngine(Map<String, CanvasNodeExecutor> executors) {
        this(new CanvasExecutionPlanner(), executors);
    }

    public CanvasExecutionEngine(CanvasExecutionPlanner planner,
                                  Map<String, CanvasNodeExecutor> executors) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(executors, "executors");
        Map<String, CanvasNodeExecutor> copy = new LinkedHashMap<>();
        executors.forEach((type, executor) -> {
            if (type == null || type.isBlank()) {
                throw new IllegalArgumentException("executor type must not be blank");
            }
            copy.put(type, Objects.requireNonNull(executor, "executor"));
        });
        this.executors = Collections.unmodifiableMap(copy);
        this.preflight = new CanvasExecutionPreflight(planner, this.executors);
    }

    public CanvasExecutionResult execute(CanvasDefinition canvas) {
        return execute(CanvasExecutionRequest.forCanvas(canvas));
    }

    public CanvasExecutionPreflightResult preflight(CanvasExecutionRequest request) {
        return preflight.inspect(request);
    }

    public CanvasExecutionResult execute(CanvasExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        CanvasDefinition canvas = request.canvas();
        Instant startedAt = Instant.now();
        CanvasExecutionPlan plan = preflight.prepare(request);
        Map<UUID, CanvasNode> nodes = indexNodes(canvas.nodes());
        Map<UUID, List<CanvasConnection>> outgoing = outgoingConnections(canvas.connections());
        Set<UUID> declaredOutputPorts = Set.copyOf(canvas.outputBindings().values());
        Map<UUID, Object> inputValues = new LinkedHashMap<>(request.initialInputs());
        Map<UUID, Object> outputValues = new LinkedHashMap<>();
        List<UUID> completed = new ArrayList<>();
        List<CanvasNodeExecutionTrace> traces = new ArrayList<>();
        List<String> consoleOutput = new ArrayList<>();

        for (UUID nodeId : plan.orderedNodeIds()) {
            CanvasNode node = nodes.get(nodeId);
            Instant nodeStartedAt = Instant.now();
            Map<UUID, Object> nodeInputs = new LinkedHashMap<>();
            for (CanvasPort port : node.ports()) {
                if (inputValues.containsKey(port.portId())) {
                    nodeInputs.put(port.portId(), inputValues.get(port.portId()));
                }
            }
            CanvasNodeExecutor executor = executors.get(node.nodeType());
            if (executor == null) {
                String message = "no executor registered for node type: " + node.nodeType();
                traces.add(failedTrace(node, nodeInputs, message, nodeStartedAt));
                return failed(request.executionId(), canvas, completed, traces, consoleOutput, outputValues,
                        nodeId, message, startedAt);
            }
            CanvasNodeExecutionContext context = new CanvasNodeExecutionContext(node, nodeInputs);
            try {
                executor.execute(node, context);
            } catch (Exception exception) {
                String message = messageOf(exception);
                traces.add(new CanvasNodeExecutionTrace(node.nodeId(), node.nodeType(), CanvasExecutionStatus.FAILED,
                        nodeInputs, context.outputsSnapshot(), context.consoleOutputSnapshot(),
                        Duration.between(nodeStartedAt, Instant.now()), message));
                consoleOutput.addAll(context.consoleOutputSnapshot());
                return failed(request.executionId(), canvas, completed, traces, consoleOutput, outputValues,
                        nodeId, message, startedAt);
            }
            Map<UUID, Object> outputs = context.outputsSnapshot();
            for (CanvasConnection connection : outgoing.getOrDefault(nodeId, List.of())) {
                if (!outputs.containsKey(connection.fromPortId())) {
                    String message = "node did not produce connected output port: " + connection.fromPortId();
                    traces.add(new CanvasNodeExecutionTrace(node.nodeId(), node.nodeType(), CanvasExecutionStatus.FAILED,
                            nodeInputs, outputs, context.consoleOutputSnapshot(),
                            Duration.between(nodeStartedAt, Instant.now()), message));
                    consoleOutput.addAll(context.consoleOutputSnapshot());
                    return failed(request.executionId(), canvas, completed, traces, consoleOutput, outputValues,
                            nodeId, message, startedAt);
                }
            }
            for (CanvasPort port : node.ports()) {
                if (declaredOutputPorts.contains(port.portId()) && !outputs.containsKey(port.portId())) {
                    String message = "node did not produce declared output port: " + port.portId();
                    traces.add(new CanvasNodeExecutionTrace(node.nodeId(), node.nodeType(),
                            CanvasExecutionStatus.FAILED, nodeInputs, outputs, context.consoleOutputSnapshot(),
                            Duration.between(nodeStartedAt, Instant.now()), message));
                    consoleOutput.addAll(context.consoleOutputSnapshot());
                    return failed(request.executionId(), canvas, completed, traces, consoleOutput, outputValues,
                            nodeId, message, startedAt);
                }
            }
            outputValues.putAll(outputs);
            traces.add(new CanvasNodeExecutionTrace(node.nodeId(), node.nodeType(), CanvasExecutionStatus.SUCCEEDED,
                    nodeInputs, outputs, context.consoleOutputSnapshot(),
                    Duration.between(nodeStartedAt, Instant.now()), ""));
            consoleOutput.addAll(context.consoleOutputSnapshot());
            completed.add(nodeId);
            for (CanvasConnection connection : outgoing.getOrDefault(nodeId, List.of())) {
                inputValues.put(connection.toPortId(), outputs.get(connection.fromPortId()));
            }
        }
        return new CanvasExecutionResult(request.executionId(), canvas.canvasId(),
                CanvasExecutionStatus.SUCCEEDED, completed, traces,
                consoleOutput, outputValues, namedOutputValues(canvas, outputValues),
                null, "", Duration.between(startedAt, Instant.now()));
    }

    private static Map<UUID, CanvasNode> indexNodes(List<CanvasNode> nodes) {
        Map<UUID, CanvasNode> indexed = new HashMap<>();
        nodes.forEach(node -> indexed.put(node.nodeId(), node));
        return indexed;
    }

    private static Map<UUID, List<CanvasConnection>> outgoingConnections(List<CanvasConnection> connections) {
        Map<UUID, List<CanvasConnection>> outgoing = new HashMap<>();
        for (CanvasConnection connection : connections) {
            outgoing.computeIfAbsent(connection.fromNodeId(), ignored -> new ArrayList<>()).add(connection);
        }
        outgoing.replaceAll((nodeId, values) -> List.copyOf(values));
        return outgoing;
    }

    private static CanvasExecutionResult failed(UUID executionId, CanvasDefinition canvas, List<UUID> completed,
                                                List<CanvasNodeExecutionTrace> traces, List<String> consoleOutput,
                                                Map<UUID, Object> outputValues,
                                                UUID nodeId,
                                                String message, Instant startedAt) {
        return new CanvasExecutionResult(executionId, canvas.canvasId(), CanvasExecutionStatus.FAILED,
                completed, traces,
                consoleOutput, outputValues, namedOutputValues(canvas, outputValues), nodeId,
                message == null || message.isBlank() ? "node execution failed" : message,
                Duration.between(startedAt, Instant.now()));
    }

    private static Map<String, Object> namedOutputValues(CanvasDefinition canvas,
                                                          Map<UUID, Object> outputValues) {
        Map<String, Object> named = new LinkedHashMap<>();
        for (Map.Entry<String, UUID> binding : canvas.outputBindings().entrySet()) {
            if (outputValues.containsKey(binding.getValue())) {
                named.put(binding.getKey(), outputValues.get(binding.getValue()));
            }
        }
        return named;
    }

    private static CanvasNodeExecutionTrace failedTrace(CanvasNode node, Map<UUID, Object> inputs,
                                                        String message, Instant startedAt) {
        return new CanvasNodeExecutionTrace(node.nodeId(), node.nodeType(), CanvasExecutionStatus.FAILED,
                inputs, Map.of(), List.of(), Duration.between(startedAt, Instant.now()), message);
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
