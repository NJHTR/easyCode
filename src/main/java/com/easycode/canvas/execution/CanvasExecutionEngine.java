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
import java.util.concurrent.CancellationException;

/**
 * Small synchronous graph runner. It dispatches node types and moves values
 * between connected ports; it does not start processes or schedule work.
 */
public final class CanvasExecutionEngine {
    private final Map<String, CanvasNodeExecutor> executors;
    private final CanvasExecutionPreflight preflight;
    private final CanvasExecutionValueSnapshotter traceSnapshotter;

    public CanvasExecutionEngine(Map<String, CanvasNodeExecutor> executors) {
        this(new CanvasExecutionPlanner(), executors, CanvasExecutionValueSnapshotter.identity());
    }

    public CanvasExecutionEngine(Map<String, CanvasNodeExecutor> executors,
                                 CanvasExecutionValueSnapshotter traceSnapshotter) {
        this(new CanvasExecutionPlanner(), executors, traceSnapshotter);
    }

    public CanvasExecutionEngine(CanvasExecutionPlanner planner,
                                  Map<String, CanvasNodeExecutor> executors) {
        this(planner, executors, CanvasExecutionValueSnapshotter.identity());
    }

    public CanvasExecutionEngine(CanvasExecutionPlanner planner,
                                 Map<String, CanvasNodeExecutor> executors,
                                 CanvasExecutionValueSnapshotter traceSnapshotter) {
        Objects.requireNonNull(planner, "planner");
        Objects.requireNonNull(executors, "executors");
        this.traceSnapshotter = Objects.requireNonNull(traceSnapshotter, "traceSnapshotter");
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
        return execute(request, CanvasExecutionObserver.noop());
    }

    /** Executes one request and reports lifecycle events inline to the observer. */
    public CanvasExecutionResult execute(CanvasExecutionRequest request,
                                         CanvasExecutionObserver observer) {
        return execute(request, observer, new CanvasExecutionCancellationToken());
    }

    /** Executes one request with a cooperative cancellation signal. */
    public CanvasExecutionResult execute(CanvasExecutionRequest request,
                                         CanvasExecutionObserver observer,
                                         CanvasExecutionCancellationToken cancellationToken) {
        return execute(request, observer, cancellationToken, new CanvasExecutionDebugger());
    }

    /** Executes a request with external node-boundary breakpoint and stepping control. */
    public CanvasExecutionResult executeDebuggable(CanvasExecutionRequest request,
                                                   CanvasExecutionObserver observer,
                                                   CanvasExecutionCancellationToken cancellationToken,
                                                   CanvasExecutionDebugger debugger) {
        return execute(request, observer, cancellationToken, debugger);
    }

    private CanvasExecutionResult execute(CanvasExecutionRequest request,
                                          CanvasExecutionObserver observer,
                                          CanvasExecutionCancellationToken cancellationToken,
                                          CanvasExecutionDebugger debugger) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(observer, "observer");
        Objects.requireNonNull(cancellationToken, "cancellationToken");
        Objects.requireNonNull(debugger, "debugger");
        CanvasDefinition canvas = request.canvas();
        Instant startedAt = Instant.now();
        CanvasExecutionPlan plan = preflight.prepare(request);
        debugger.validateBreakpoints(plan.orderedNodeIds());
        try {
            long eventSequence = 0L;
            eventSequence = emit(observer, request.executionId(), CanvasExecutionEventType.STARTED,
                null, "", eventSequence, Map.of(), Map.of(), List.of());
            Map<UUID, CanvasNode> nodes = indexNodes(canvas.nodes());
            Map<UUID, List<CanvasConnection>> outgoing = outgoingConnections(canvas.connections());
            Set<UUID> declaredOutputPorts = Set.copyOf(canvas.outputBindings().values());
            Map<UUID, Object> inputValues = new LinkedHashMap<>(request.initialInputs());
            Map<UUID, Object> outputValues = new LinkedHashMap<>();
            List<UUID> completed = new ArrayList<>();
            List<CanvasNodeExecutionTrace> traces = new ArrayList<>();
            List<String> consoleOutput = new ArrayList<>();

        if (cancellationToken.isCancellationRequested()) {
            return cancelled(request.executionId(), observer, canvas, completed, traces,
                    consoleOutput, outputValues, null, Map.of(), Map.of(), List.of(), eventSequence, startedAt,
                    debugger);
        }

        for (UUID nodeId : plan.orderedNodeIds()) {
            if (cancellationToken.isCancellationRequested()) {
                return cancelled(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues, null, Map.of(), Map.of(), List.of(), eventSequence, startedAt,
                        debugger);
            }
            CanvasNode node = nodes.get(nodeId);
            Instant nodeStartedAt = Instant.now();
            Map<UUID, Object> nodeInputs = new LinkedHashMap<>();
            for (CanvasPort port : node.ports()) {
                if (inputValues.containsKey(port.portId())) {
                    nodeInputs.put(port.portId(), inputValues.get(port.portId()));
                }
            }
            Map<UUID, Object> traceInputs = CanvasExecutionValueSnapshots.snapshot(
                    nodeInputs, "inputs", traceSnapshotter);
            eventSequence = debugger.beforeNode(nodeId, nodeInputs, cancellationToken, observer,
                    request.executionId(), eventSequence);
            if (cancellationToken.isCancellationRequested()) {
                return cancelledBeforeNode(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues, node, nodeInputs, traceInputs, eventSequence, startedAt,
                        debugger);
            }
            eventSequence = emit(observer, request.executionId(), CanvasExecutionEventType.NODE_STARTED,
                    nodeId, "", eventSequence, nodeInputs, Map.of(), List.of());
            CanvasNodeExecutor executor = executors.get(node.nodeType());
            if (executor == null) {
                String message = "no executor registered for node type: " + node.nodeType();
                traces.add(failedTrace(node, traceInputs, message, nodeStartedAt));
                return failed(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues,
                        nodeId, message, startedAt, eventSequence, nodeInputs, Map.of(), List.of(), null, debugger);
            }
            CanvasNodeExecutionContext context = new CanvasNodeExecutionContext(node, nodeInputs, cancellationToken);
            try {
                executor.execute(node, context);
            } catch (CancellationException exception) {
                if (cancellationToken.isCancellationRequested()) {
                    return cancelledNode(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues, node, nodeInputs, traceInputs, context, eventSequence,
                        nodeStartedAt, startedAt, traceSnapshotter, debugger);
                }
                return failedNode(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues, node, nodeInputs, traceInputs, context, exception,
                        eventSequence, nodeStartedAt, startedAt, traceSnapshotter, debugger);
            } catch (Exception exception) {
                return failedNode(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues, node, nodeInputs, traceInputs, context, exception,
                        eventSequence, nodeStartedAt, startedAt, traceSnapshotter, debugger);
            }
            if (cancellationToken.isCancellationRequested()) {
                return cancelledNode(request.executionId(), observer, canvas, completed, traces,
                        consoleOutput, outputValues, node, nodeInputs, traceInputs, context, eventSequence,
                        nodeStartedAt, startedAt, traceSnapshotter, debugger);
            }
            Map<UUID, Object> outputs = context.outputsSnapshot();
            for (CanvasConnection connection : outgoing.getOrDefault(nodeId, List.of())) {
                if (!outputs.containsKey(connection.fromPortId())) {
                    String message = "node did not produce connected output port: " + connection.fromPortId();
                    traces.add(CanvasNodeExecutionTrace.fromSnapshots(node.nodeId(), node.nodeType(),
                            CanvasExecutionStatus.FAILED, traceInputs,
                            snapshot(outputs, traceSnapshotter), context.consoleOutputSnapshot(),
                            Duration.between(nodeStartedAt, Instant.now()), message, null));
                    consoleOutput.addAll(context.consoleOutputSnapshot());
                    return failed(request.executionId(), observer, canvas, completed, traces,
                            consoleOutput, outputValues,
                            nodeId, message, startedAt, eventSequence, nodeInputs,
                            outputs, context.consoleOutputSnapshot(), null, debugger);
                }
            }
            for (CanvasPort port : node.ports()) {
                if (declaredOutputPorts.contains(port.portId()) && !outputs.containsKey(port.portId())) {
                    String message = "node did not produce declared output port: " + port.portId();
                    traces.add(CanvasNodeExecutionTrace.fromSnapshots(node.nodeId(), node.nodeType(),
                            CanvasExecutionStatus.FAILED, traceInputs,
                            snapshot(outputs, traceSnapshotter), context.consoleOutputSnapshot(),
                            Duration.between(nodeStartedAt, Instant.now()), message, null));
                    consoleOutput.addAll(context.consoleOutputSnapshot());
                    return failed(request.executionId(), observer, canvas, completed, traces,
                            consoleOutput, outputValues,
                            nodeId, message, startedAt, eventSequence, nodeInputs,
                            outputs, context.consoleOutputSnapshot(), null, debugger);
                }
            }
            outputValues.putAll(outputs);
            traces.add(CanvasNodeExecutionTrace.fromSnapshots(node.nodeId(), node.nodeType(),
                    CanvasExecutionStatus.SUCCEEDED, traceInputs,
                    snapshot(outputs, traceSnapshotter), context.consoleOutputSnapshot(),
                    Duration.between(nodeStartedAt, Instant.now()), "", null));
            consoleOutput.addAll(context.consoleOutputSnapshot());
            completed.add(nodeId);
            eventSequence = emit(observer, request.executionId(), CanvasExecutionEventType.NODE_SUCCEEDED,
                    nodeId, "", eventSequence, nodeInputs, outputs, context.consoleOutputSnapshot());
            for (CanvasConnection connection : outgoing.getOrDefault(nodeId, List.of())) {
                inputValues.put(connection.toPortId(), outputs.get(connection.fromPortId()));
            }
            debugger.afterNode();
        }
        CanvasExecutionResult result = new CanvasExecutionResult(request.executionId(), canvas.canvasId(),
                CanvasExecutionStatus.SUCCEEDED, completed, traces,
                consoleOutput, outputValues, namedOutputValues(canvas, outputValues),
                null, "", Duration.between(startedAt, Instant.now()));
        debugger.complete();
        emit(observer, request.executionId(), CanvasExecutionEventType.SUCCEEDED,
                null, "", eventSequence, Map.of(), Map.of(), List.of());
            return result;
        } finally {
            // Observer failures must not leave reusable debugger state behind.
            debugger.complete();
        }
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

    private static CanvasExecutionResult cancelledNode(UUID executionId, CanvasExecutionObserver observer,
                                                       CanvasDefinition canvas, List<UUID> completed,
                                                       List<CanvasNodeExecutionTrace> traces,
                                                       List<String> consoleOutput,
                                                       Map<UUID, Object> outputValues,
                                                       CanvasNode node,
                                                       Map<UUID, Object> inputs,
                                                       Map<UUID, Object> traceInputs,
                                                       CanvasNodeExecutionContext context,
                                                       long eventSequence,
                                                       Instant nodeStartedAt,
                                                       Instant startedAt,
                                                       CanvasExecutionValueSnapshotter snapshotter,
                                                       CanvasExecutionDebugger debugger) {
        Map<UUID, Object> outputs = context.outputsSnapshot();
        List<String> nodeConsoleOutput = context.consoleOutputSnapshot();
        traces.add(CanvasNodeExecutionTrace.fromSnapshots(node.nodeId(), node.nodeType(),
                CanvasExecutionStatus.CANCELLED, traceInputs, snapshot(outputs, snapshotter),
                nodeConsoleOutput, Duration.between(nodeStartedAt, Instant.now()), "", null));
        consoleOutput.addAll(nodeConsoleOutput);
        return cancelled(executionId, observer, canvas, completed, traces, consoleOutput, outputValues,
                node.nodeId(), inputs, outputs, nodeConsoleOutput, eventSequence, startedAt, debugger);
    }

    private static CanvasExecutionResult cancelledBeforeNode(UUID executionId,
                                                             CanvasExecutionObserver observer,
                                                             CanvasDefinition canvas,
                                                             List<UUID> completed,
                                                             List<CanvasNodeExecutionTrace> traces,
                                                             List<String> consoleOutput,
                                                             Map<UUID, Object> outputValues,
                                                             CanvasNode node,
                                                             Map<UUID, Object> inputs,
                                                             Map<UUID, Object> traceInputs,
                                                             long eventSequence,
                                                             Instant startedAt,
                                                             CanvasExecutionDebugger debugger) {
        traces.add(CanvasNodeExecutionTrace.fromSnapshots(node.nodeId(), node.nodeType(),
                CanvasExecutionStatus.CANCELLED, traceInputs, Map.of(), List.of(), Duration.ZERO, "", null));
        return cancelled(executionId, observer, canvas, completed, traces, consoleOutput, outputValues,
                node.nodeId(), inputs, Map.of(), List.of(), eventSequence, startedAt, debugger);
    }

    private static CanvasExecutionResult failedNode(UUID executionId, CanvasExecutionObserver observer,
                                                    CanvasDefinition canvas, List<UUID> completed,
                                                    List<CanvasNodeExecutionTrace> traces,
                                                    List<String> consoleOutput,
                                                    Map<UUID, Object> outputValues,
                                                    CanvasNode node,
                                                    Map<UUID, Object> inputs,
                                                    Map<UUID, Object> traceInputs,
                                                    CanvasNodeExecutionContext context,
                                                    Exception exception,
                                                    long eventSequence,
                                                    Instant nodeStartedAt,
                                                    Instant startedAt,
                                                    CanvasExecutionValueSnapshotter snapshotter,
                                                    CanvasExecutionDebugger debugger) {
        String message = messageOf(exception);
        CanvasExecutionFailure failureDetails = CanvasExecutionFailure.from(exception);
        Map<UUID, Object> outputs = context.outputsSnapshot();
        List<String> nodeConsoleOutput = context.consoleOutputSnapshot();
        traces.add(CanvasNodeExecutionTrace.fromSnapshots(node.nodeId(), node.nodeType(),
                CanvasExecutionStatus.FAILED, traceInputs, snapshot(outputs, snapshotter),
                nodeConsoleOutput, Duration.between(nodeStartedAt, Instant.now()), message, failureDetails));
        consoleOutput.addAll(nodeConsoleOutput);
        return failed(executionId, observer, canvas, completed, traces, consoleOutput, outputValues,
                node.nodeId(), message, startedAt, eventSequence, inputs, outputs, nodeConsoleOutput,
                failureDetails, debugger);
    }

    private static CanvasExecutionResult cancelled(UUID executionId, CanvasExecutionObserver observer,
                                                   CanvasDefinition canvas, List<UUID> completed,
                                                   List<CanvasNodeExecutionTrace> traces,
                                                   List<String> consoleOutput,
                                                   Map<UUID, Object> outputValues,
                                                   UUID nodeId,
                                                   Map<UUID, Object> inputs,
                                                   Map<UUID, Object> outputs,
                                                   List<String> nodeConsoleOutput,
                                                   long eventSequence,
                                                   Instant startedAt,
                                                   CanvasExecutionDebugger debugger) {
        CanvasExecutionResult result = new CanvasExecutionResult(executionId, canvas.canvasId(),
                CanvasExecutionStatus.CANCELLED, completed, traces, consoleOutput,
                outputValues, namedOutputValues(canvas, outputValues), null, "",
                Duration.between(startedAt, Instant.now()));
        debugger.complete();
        if (nodeId != null) {
            eventSequence = emit(observer, executionId, CanvasExecutionEventType.NODE_CANCELLED,
                    nodeId, "canvas execution cancelled", eventSequence, inputs, outputs, nodeConsoleOutput);
        }
        emit(observer, executionId, CanvasExecutionEventType.CANCELLED,
                null, "canvas execution cancelled", eventSequence, Map.of(), Map.of(), List.of());
        return result;
    }

    private static CanvasExecutionResult failed(UUID executionId, CanvasExecutionObserver observer,
                                                CanvasDefinition canvas, List<UUID> completed,
                                                List<CanvasNodeExecutionTrace> traces, List<String> consoleOutput,
                                                Map<UUID, Object> outputValues,
                                                UUID nodeId,
                                                String message, Instant startedAt,
                                                long eventSequence,
                                                Map<UUID, Object> inputs,
                                                Map<UUID, Object> outputs,
                                                List<String> nodeConsoleOutput,
                                                CanvasExecutionFailure failureDetails,
                                                CanvasExecutionDebugger debugger) {
        String failure = message == null || message.isBlank() ? "node execution failed" : message;
        CanvasExecutionResult result = new CanvasExecutionResult(executionId, canvas.canvasId(),
                CanvasExecutionStatus.FAILED,
                completed, traces,
                consoleOutput, outputValues, namedOutputValues(canvas, outputValues), nodeId,
                failure,
                Duration.between(startedAt, Instant.now()));
        debugger.complete();
        eventSequence = emit(observer, executionId, CanvasExecutionEventType.NODE_FAILED,
                nodeId, failure, eventSequence, inputs, outputs, nodeConsoleOutput, failureDetails);
        emit(observer, executionId, CanvasExecutionEventType.FAILED,
                null, failure, eventSequence, Map.of(), Map.of(), List.of());
        return result;
    }

    private static long emit(CanvasExecutionObserver observer, UUID executionId,
                             CanvasExecutionEventType type, UUID nodeId, String message,
                             long sequence, Map<UUID, Object> inputs,
                             Map<UUID, Object> outputs, List<String> consoleOutput) {
        return emit(observer, executionId, type, nodeId, message, sequence,
                inputs, outputs, consoleOutput, null);
    }

    private static long emit(CanvasExecutionObserver observer, UUID executionId,
                             CanvasExecutionEventType type, UUID nodeId, String message,
                             long sequence, Map<UUID, Object> inputs,
                             Map<UUID, Object> outputs, List<String> consoleOutput,
                             CanvasExecutionFailure failureDetails) {
        observer.onEvent(new CanvasExecutionEvent(executionId, type, nodeId, message, sequence,
                inputs, outputs, consoleOutput, Instant.now(), failureDetails));
        return sequence + 1;
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

    private static Map<UUID, Object> snapshot(Map<UUID, Object> values,
                                               CanvasExecutionValueSnapshotter snapshotter) {
        return CanvasExecutionValueSnapshots.snapshot(values, "outputs", snapshotter);
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
