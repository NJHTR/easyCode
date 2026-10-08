package com.easycode.canvas.integration;

import com.easycode.canvas.execution.CanvasExecutionEngine;
import com.easycode.canvas.execution.CanvasExecutionRequest;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.CanvasNodeExecutionTrace;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasExecutionEngineIntegrationTest {
    @Test
    void dispatchesNodesInPlanOrderAndTransfersPortValues() {
        Fixture source = fixture("source", true, false);
        Fixture target = fixture("target", false, true);
        AtomicReference<Object> received = new AtomicReference<>();
        CanvasExecutionEngine engine = new CanvasExecutionEngine(Map.of(
                "source", (node, context) -> context.output(source.outputPort(), "hello"),
                "target", (node, context) -> received.set(context.input(target.inputPort()))));

        CanvasExecutionResult result = engine.execute(canvas(List.of(source.node(), target.node()),
                List.of(connection(source, target))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of(source.id(), target.id()), result.completedNodeIds());
        assertEquals("hello", received.get());
        assertEquals("hello", result.nodeTraces().get(0).outputs().get(source.outputPort()));
        assertEquals("hello", result.nodeTraces().get(1).inputs().get(target.inputPort()));
    }

    @Test
    void reportsUnknownNodeTypeWithoutExecutingIt() {
        Fixture node = fixture("unknown", false, false);

        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of())
                .execute(canvas(List.of(node.node()), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(node.id(), result.failedNodeId());
        assertEquals(List.of(), result.completedNodeIds());
        assertEquals(CanvasExecutionStatus.FAILED, result.nodeTraces().get(0).status());
    }

    @Test
    void unknownNodeTracePreservesInputsAlreadyDeliveredByUpstreamNodes() {
        Fixture source = fixture("source", true, false);
        Fixture unknown = fixture("unknown", false, true);
        CanvasNode sourceNode = new CanvasNode(source.id(), "source", CanvasBuiltinExecutors.CONSTANT,
                Map.of("value", "available"), source.node().ports());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all())
                .execute(canvas(List.of(sourceNode, unknown.node()), List.of(connection(source, unknown))));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(unknown.id(), result.failedNodeId());
        assertEquals("available", result.nodeTraces().get(1).inputs().get(unknown.inputPort()));
        assertEquals("no executor registered for node type: unknown", result.failureMessage());
    }

    @Test
    void reportsExecutorFailureAndPreservesCompletedPrefix() {
        Fixture first = fixture("first", false, false);
        Fixture second = fixture("second", false, false);
        CanvasExecutionEngine engine = new CanvasExecutionEngine(Map.of(
                "first", (node, context) -> { },
                "second", (node, context) -> { throw new IllegalStateException("bad node"); }));

        CanvasExecutionResult result = engine.execute(canvas(List.of(first.node(), second.node()), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(second.id(), result.failedNodeId());
        assertEquals(List.of(first.id()), result.completedNodeIds());
        assertEquals("bad node", result.failureMessage());
        assertEquals("bad node", result.nodeTraces().get(1).failureMessage());
    }

    @Test
    void excludesOutputsPublishedByTheFailingNode() {
        Fixture failing = fixture("failing", true, false);
        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "failing", (node, context) -> {
                    context.output(failing.outputPort(), "partial");
                    throw new IllegalStateException("failed after output");
                }))
                .execute(canvas(List.of(failing.node()), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals("partial", result.nodeTraces().get(0).outputs().get(failing.outputPort()));
        assertEquals(false, result.outputValues().containsKey(failing.outputPort()));
    }

    @Test
    void rejectsExecutorWritesToAnInputPort() {
        Fixture node = fixture("writer", true, true);
        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "writer", (current, context) -> context.output(node.inputPort(), "invalid")))
                .execute(canvas(List.of(node.node()), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(node.id(), result.failedNodeId());
    }

    @Test
    void builtinNodesProduceConsoleOutputAndTransferConfigurationValue() {
        Fixture constant = fixture("constant", true, false);
        Fixture print = fixture("print", false, true);
        CanvasNode constantNode = new CanvasNode(constant.id(), "constant", "constant", Map.of("value", "hello"),
                constant.node().ports());
        CanvasNode printNode = new CanvasNode(print.id(), "print", "print", Map.of(), print.node().ports());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all())
                .execute(canvas(List.of(constantNode, printNode), List.of(connection(constant, print))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of("hello"), result.consoleOutput());
        assertEquals(List.of("hello"), result.nodeTraces().get(1).consoleOutput());
    }

    @Test
    void oneOutputPortFansOutToMultipleDownstreamInputs() {
        Fixture source = fixture("source", true, false);
        Fixture first = fixture("first", true, true);
        Fixture second = fixture("second", false, true);
        CanvasNode sourceNode = new CanvasNode(source.id(), "source", CanvasBuiltinExecutors.CONSTANT,
                Map.of("value", "fan-out"), source.node().ports());
        CanvasNode firstNode = new CanvasNode(first.id(), "first", CanvasBuiltinExecutors.PASSTHROUGH,
                Map.of(), first.node().ports());
        CanvasNode secondNode = new CanvasNode(second.id(), "second", CanvasBuiltinExecutors.PRINT,
                Map.of(), second.node().ports());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(sourceNode, firstNode, secondNode), List.of(
                        connection(source, first), connection(source, second))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals("fan-out", result.nodeTraces().get(1).inputs().get(first.inputPort()));
        assertEquals(List.of("fan-out"), result.consoleOutput());
    }

    @Test
    void failsAtNodeWhenConnectedOutputWasNotPublished() {
        Fixture source = fixture("source", true, false);
        Fixture target = fixture("target", false, true);
        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "source", (node, context) -> { },
                "target", (node, context) -> { }))
                .execute(canvas(List.of(source.node(), target.node()), List.of(connection(source, target))));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(source.id(), result.failedNodeId());
        assertEquals("node did not produce connected output port: " + source.outputPort(),
                result.failureMessage());
        assertEquals(List.of(), result.completedNodeIds());
    }

    @Test
    void allowsUnconnectedOutputToRemainUnpublished() {
        Fixture terminal = fixture("terminal", true, false);

        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "terminal", (node, context) -> { }))
                .execute(canvas(List.of(terminal.node()), List.of()));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of(terminal.id()), result.completedNodeIds());
        assertEquals(Map.of(), result.nodeTraces().get(0).outputs());
    }

    @Test
    void builtinAddNodeCombinesExplicitInputPorts() {
        Fixture left = fixture("left", true, false);
        Fixture right = fixture("right", true, false);
        Fixture add = fixture("add", true, true);
        UUID addRightInputId = UUID.randomUUID();
        CanvasNode addNode = new CanvasNode(add.id(), "add", CanvasBuiltinExecutors.ADD, Map.of(), List.of(
                new CanvasPort(add.inputPort(), "left", CanvasPortDirection.INPUT),
                new CanvasPort(addRightInputId, "right", CanvasPortDirection.INPUT),
                new CanvasPort(add.outputPort(), "out", CanvasPortDirection.OUTPUT)));
        Fixture print = fixture("print", false, true);
        CanvasNode leftNode = constantNode(left, 1);
        CanvasNode rightNode = constantNode(right, 2.25);
        CanvasNode printNode = new CanvasNode(print.id(), "print", CanvasBuiltinExecutors.PRINT, Map.of(),
                print.node().ports());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(addNode, printNode, leftNode, rightNode), List.of(
                        connection(left, add, add.inputPort()),
                        connection(right, add, addRightInputId),
                        connection(add, print))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(new BigDecimal("3.25"), result.nodeTraces().get(2).outputs().get(add.outputPort()));
        assertEquals(new BigDecimal("3.25"), result.outputValues().get(add.outputPort()));
        assertEquals(List.of("3.25"), result.consoleOutput());
    }

    @Test
    void oneOutputCanDriveMultipleInputsOnTheSameNode() {
        Fixture source = fixture("source", true, false);
        Fixture add = fixture("add", true, true);
        UUID addRightInputId = UUID.randomUUID();
        CanvasNode addNode = new CanvasNode(add.id(), "add", CanvasBuiltinExecutors.ADD, Map.of(), List.of(
                new CanvasPort(add.inputPort(), "left", CanvasPortDirection.INPUT),
                new CanvasPort(addRightInputId, "right", CanvasPortDirection.INPUT),
                new CanvasPort(add.outputPort(), "out", CanvasPortDirection.OUTPUT)));
        Fixture print = fixture("print", false, true);
        CanvasNode sourceNode = constantNode(source, 2.25);
        CanvasNode printNode = new CanvasNode(print.id(), "print", CanvasBuiltinExecutors.PRINT, Map.of(),
                print.node().ports());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(sourceNode, addNode, printNode), List.of(
                        connection(source, add, add.inputPort()),
                        connection(source, add, addRightInputId),
                        connection(add, print))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(2.25, result.nodeTraces().get(1).inputs().get(add.inputPort()));
        assertEquals(2.25, result.nodeTraces().get(1).inputs().get(addRightInputId));
        assertEquals(new BigDecimal("4.5"), result.nodeTraces().get(1).outputs().get(add.outputPort()));
        assertEquals(List.of("4.5"), result.consoleOutput());
    }

    @Test
    void propagatesNullAsAValueAcrossAConnection() {
        Fixture constant = fixture("constant", true, false);
        Fixture print = fixture("print", false, true);
        Map<String, Object> configuration = new java.util.LinkedHashMap<>();
        configuration.put("value", null);
        CanvasNode constantNode = new CanvasNode(constant.id(), "constant", CanvasBuiltinExecutors.CONSTANT,
                configuration, constant.node().ports());
        CanvasNode printNode = new CanvasNode(print.id(), "print", CanvasBuiltinExecutors.PRINT, Map.of(),
                print.node().ports());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(constantNode, printNode), List.of(connection(constant, print))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(1, result.nodeTraces().get(1).inputs().size());
        assertEquals(true, result.nodeTraces().get(1).inputs().containsKey(print.inputPort()));
        assertEquals(null, result.nodeTraces().get(1).inputs().get(print.inputPort()));
        assertEquals(true, result.outputValues().containsKey(constant.outputPort()));
        assertEquals(null, result.outputValues().get(constant.outputPort()));
        assertEquals(List.of("null"), result.consoleOutput());
    }

    @Test
    void builtinAddNodeFailsWhenAnInputPortIsUnconnected() {
        Fixture left = fixture("left", true, false);
        Fixture add = fixture("add", true, true);
        UUID addRightInputId = UUID.randomUUID();
        CanvasNode addNode = new CanvasNode(add.id(), "add", CanvasBuiltinExecutors.ADD, Map.of(), List.of(
                new CanvasPort(add.inputPort(), "left", CanvasPortDirection.INPUT),
                new CanvasPort(addRightInputId, "right", CanvasPortDirection.INPUT),
                new CanvasPort(add.outputPort(), "out", CanvasPortDirection.OUTPUT)));

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(constantNode(left, 2.25), addNode), List.of(
                        connection(left, add, add.inputPort()))));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(add.id(), result.failedNodeId());
        assertEquals("add node requires a numeric input on port: right", result.failureMessage());
        assertEquals(2.25, result.nodeTraces().get(1).inputs().get(add.inputPort()));
    }

    @Test
    void builtinPrintNodeFailsWhenItsInputIsUnconnected() {
        Fixture print = fixture("print", false, true);

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all())
                .execute(canvas(List.of(new CanvasNode(print.id(), "print", CanvasBuiltinExecutors.PRINT,
                        Map.of(), print.node().ports())), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(print.id(), result.failedNodeId());
        assertEquals("node requires an input on port: in", result.failureMessage());
        assertEquals(List.of(), result.consoleOutput());
    }

    @Test
    void builtinPassthroughNodePreservesConnectedNullButRejectsMissingInput() {
        Fixture source = fixture("source", true, false);
        Fixture passthrough = fixture("passthrough", true, true);
        Map<String, Object> nullConfiguration = new java.util.LinkedHashMap<>();
        nullConfiguration.put("value", null);
        CanvasNode sourceNode = new CanvasNode(source.id(), "source", CanvasBuiltinExecutors.CONSTANT,
                nullConfiguration, source.node().ports());
        CanvasNode passthroughNode = new CanvasNode(passthrough.id(), "passthrough",
                CanvasBuiltinExecutors.PASSTHROUGH, Map.of(), passthrough.node().ports());

        CanvasExecutionResult connectedNull = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(sourceNode, passthroughNode), List.of(connection(source, passthrough))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, connectedNull.status());
        assertEquals(true, connectedNull.nodeTraces().get(1).outputs().containsKey(passthrough.outputPort()));
        assertEquals(null, connectedNull.nodeTraces().get(1).outputs().get(passthrough.outputPort()));

        CanvasExecutionResult missing = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(passthroughNode), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, missing.status());
        assertEquals("node requires an input on port: in", missing.failureMessage());
    }

    @Test
    void requestCanProvideAnInitialInputToAnUnconnectedPort() {
        Fixture passthrough = fixture("passthrough", true, true);
        CanvasNode passthroughNode = new CanvasNode(passthrough.id(), "passthrough",
                CanvasBuiltinExecutors.PASSTHROUGH, Map.of(), passthrough.node().ports());
        CanvasDefinition canvas = canvas(List.of(passthroughNode), List.of());

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                CanvasExecutionRequest.withInputs(canvas, List.of(passthrough.id()),
                        Map.of(passthrough.inputPort(), "runtime-value")));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals("runtime-value", result.nodeTraces().get(0).inputs().get(passthrough.inputPort()));
        assertEquals("runtime-value", result.nodeTraces().get(0).outputs().get(passthrough.outputPort()));
    }

    @Test
    void requestCanProvideNullAsAnInitialInput() {
        Fixture passthrough = fixture("passthrough", true, true);
        CanvasNode passthroughNode = new CanvasNode(passthrough.id(), "passthrough",
                CanvasBuiltinExecutors.PASSTHROUGH, Map.of(), passthrough.node().ports());
        Map<UUID, Object> initialInputs = new java.util.LinkedHashMap<>();
        initialInputs.put(passthrough.inputPort(), null);

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                CanvasExecutionRequest.withInputs(canvas(List.of(passthroughNode), List.of()),
                        List.of(passthrough.id()), initialInputs));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(true, result.nodeTraces().get(0).inputs().containsKey(passthrough.inputPort()));
        assertEquals(null, result.nodeTraces().get(0).outputs().get(passthrough.outputPort()));
    }

    @Test
    void requestRejectsUnknownOrConnectedInitialInputPorts() {
        Fixture source = fixture("source", true, false);
        Fixture target = fixture("target", true, true);
        CanvasDefinition canvas = canvas(List.of(source.node(), target.node()), List.of(connection(source, target)));
        CanvasExecutionEngine engine = new CanvasExecutionEngine(Map.of(
                "source", (node, context) -> context.output(source.outputPort(), "source"),
                "target", (node, context) -> { }));

        assertThrows(IllegalArgumentException.class, () -> engine.execute(CanvasExecutionRequest.withInputs(
                canvas, List.of(source.id()), Map.of(UUID.randomUUID(), "unknown"))));
        assertThrows(IllegalArgumentException.class, () -> engine.execute(CanvasExecutionRequest.withInputs(
                canvas, List.of(source.id()), Map.of(target.inputPort(), "conflict"))));
    }

    @Test
    void requestRejectsOutputAndOutOfScopeInitialInputPorts() {
        Fixture selected = fixture("selected", true, true);
        Fixture excluded = fixture("excluded", false, true);
        CanvasDefinition canvas = canvas(List.of(selected.node(), excluded.node()), List.of());
        CanvasExecutionEngine engine = new CanvasExecutionEngine(Map.of(
                "selected", (node, context) -> { },
                "excluded", (node, context) -> { }));

        IllegalArgumentException outputFailure = assertThrows(IllegalArgumentException.class,
                () -> engine.execute(CanvasExecutionRequest.withInputs(canvas, List.of(selected.id()),
                        Map.of(selected.outputPort(), "invalid"))));
        assertEquals("initial input must target an input port: " + selected.outputPort(),
                outputFailure.getMessage());

        IllegalArgumentException scopeFailure = assertThrows(IllegalArgumentException.class,
                () -> engine.execute(CanvasExecutionRequest.withInputs(canvas, List.of(selected.id()),
                        Map.of(excluded.inputPort(), "excluded"))));
        assertEquals("initial input targets a node outside the execution scope: " + excluded.id(),
                scopeFailure.getMessage());
    }

    @Test
    void builtinAddNodeRejectsNonNumericInput() {
        Fixture left = fixture("left", true, false);
        Fixture right = fixture("right", true, false);
        Fixture add = fixture("add", true, true);
        UUID addRightInputId = UUID.randomUUID();
        CanvasNode addNode = new CanvasNode(add.id(), "add", CanvasBuiltinExecutors.ADD, Map.of(), List.of(
                new CanvasPort(add.inputPort(), "left", CanvasPortDirection.INPUT),
                new CanvasPort(addRightInputId, "right", CanvasPortDirection.INPUT),
                new CanvasPort(add.outputPort(), "out", CanvasPortDirection.OUTPUT)));

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                canvas(List.of(constantNode(left, "not-a-number"), constantNode(right, 2), addNode), List.of(
                        connection(left, add, add.inputPort()),
                        connection(right, add, addRightInputId))));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(add.id(), result.failedNodeId());
        assertEquals("add node requires a numeric input on port: left", result.failureMessage());
    }

    @Test
    void executesEmptyCanvasSuccessfully() {
        CanvasDefinition canvas = CanvasDefinition.empty(UUID.randomUUID(), "empty");

        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of()).execute(canvas);

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of(), result.completedNodeIds());
        assertEquals(List.of(), result.nodeTraces());
    }

    @Test
    void successfulResultAndTraceCannotCarryFailureMessages() {
        assertThrows(IllegalArgumentException.class, () -> new CanvasExecutionResult(
                UUID.randomUUID(), CanvasExecutionStatus.SUCCEEDED, List.of(), List.of(), List.of(),
                null, "unexpected failure", Duration.ZERO));
        assertThrows(IllegalArgumentException.class, () -> new CanvasNodeExecutionTrace(
                UUID.randomUUID(), "test", CanvasExecutionStatus.SUCCEEDED, Map.of(), Map.of(), List.of(),
                Duration.ZERO, "unexpected failure"));
    }

    @Test
    void executionResultExposesImmutablePublishedOutputValues() {
        Fixture constant = fixture("constant", true, false);
        CanvasNode constantNode = constantNode(constant, "value");

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all())
                .execute(canvas(List.of(constantNode), List.of()));

        assertEquals("value", result.outputValues().get(constant.outputPort()));
        assertThrows(UnsupportedOperationException.class,
                () -> result.outputValues().put(UUID.randomUUID(), "not-allowed"));
    }

    @Test
    void executesCanvasWithNamedInputsAndReturnsNamedOutputs() {
        Fixture add = fixture("add", true, true);
        UUID rightInput = UUID.randomUUID();
        CanvasNode addNode = new CanvasNode(add.id(), "add", CanvasBuiltinExecutors.ADD, Map.of(), List.of(
                new CanvasPort(add.inputPort(), "left", CanvasPortDirection.INPUT),
                new CanvasPort(rightInput, "right", CanvasPortDirection.INPUT),
                new CanvasPort(add.outputPort(), "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "named-add", List.of(addNode),
                List.of(), Map.of("left", add.inputPort(), "right", rightInput),
                Map.of("sum", add.outputPort()));

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                CanvasExecutionRequest.withNamedInputs(canvas, List.of(add.id()),
                        Map.of("left", 10, "right", 20)));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(0, new BigDecimal("30")
                .compareTo((BigDecimal) result.namedOutputValues().get("sum")));
        assertThrows(UnsupportedOperationException.class,
                () -> result.namedOutputValues().put("other", 1));
    }

    @Test
    void namedInputRejectsAnUnknownCanvasInput() {
        Fixture passthrough = fixture("passthrough", true, true);
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "named-input",
                List.of(passthrough.node()), List.of(), Map.of("value", passthrough.inputPort()), Map.of());

        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> CanvasExecutionRequest.withNamedInputs(canvas, List.of(passthrough.id()),
                        Map.of("missing", "value")));

        assertEquals("unknown canvas input: missing", failure.getMessage());
    }

    @Test
    void namedCanvasOutputPreservesAnExplicitNull() {
        Fixture passthrough = fixture("passthrough", true, true);
        CanvasNode node = new CanvasNode(passthrough.id(), "passthrough",
                CanvasBuiltinExecutors.PASSTHROUGH, Map.of(), passthrough.node().ports());
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "named-null",
                List.of(node), List.of(), Map.of("value", passthrough.inputPort()),
                Map.of("result", passthrough.outputPort()));
        Map<String, Object> inputs = new java.util.LinkedHashMap<>();
        inputs.put("value", null);

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all()).execute(
                CanvasExecutionRequest.withNamedInputs(canvas, inputs));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(true, result.namedOutputValues().containsKey("result"));
        assertEquals(null, result.namedOutputValues().get("result"));
    }

    @Test
    void declaredCanvasOutputMustBePublished() {
        Fixture node = fixture("optional-output", true, false);
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "declared-output",
                List.of(node.node()), List.of(), Map.of(), Map.of("result", node.outputPort()));

        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "optional-output", (current, context) -> { }))
                .execute(canvas);

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals("node did not produce declared output port: " + node.outputPort(),
                result.failureMessage());
        assertEquals(false, result.namedOutputValues().containsKey("result"));
    }

    @Test
    void executorCannotReadAnUndeclaredInputPort() {
        Fixture node = fixture("reader", true, true);
        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "reader", (current, context) -> context.input(node.outputPort())))
                .execute(canvas(List.of(node.node()), List.of()));

        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(node.id(), result.failedNodeId());
        assertEquals("node cannot read from undeclared input port: " + node.outputPort(),
                result.failureMessage());
    }

    @Test
    void explicitEntryExecutesOnlyItsReachableSubgraph() {
        Fixture selected = fixture("selected", true, false);
        Fixture print = fixture("print", false, true);
        Fixture excluded = fixture("excluded", false, false);
        CanvasNode selectedNode = constantNode(selected, "selected");
        CanvasNode printNode = new CanvasNode(print.id(), "print", CanvasBuiltinExecutors.PRINT,
                Map.of(), print.node().ports());
        CanvasNode excludedNode = new CanvasNode(excluded.id(), "excluded", "unknown",
                Map.of(), excluded.node().ports());
        CanvasDefinition canvas = canvas(List.of(selectedNode, printNode, excludedNode),
                List.of(connection(selected, print)));

        CanvasExecutionResult result = new CanvasExecutionEngine(CanvasBuiltinExecutors.all())
                .execute(CanvasExecutionRequest.fromEntries(canvas, List.of(selected.id())));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of(selected.id(), print.id()), result.completedNodeIds());
        assertEquals(List.of("selected"), result.consoleOutput());
    }

    @Test
    void explicitEntriesKeepDeclarationOrder() {
        Fixture first = fixture("first", false, false);
        Fixture second = fixture("second", false, false);
        CanvasDefinition canvas = canvas(List.of(first.node(), second.node()), List.of());

        CanvasExecutionResult result = new CanvasExecutionEngine(Map.of(
                "first", (node, context) -> { },
                "second", (node, context) -> { }))
                .execute(CanvasExecutionRequest.fromEntries(canvas, List.of(second.id(), first.id())));

        assertEquals(List.of(first.id(), second.id()), result.completedNodeIds());
    }

    @Test
    void explicitEntryMustExistAndHaveNoIncomingConnection() {
        Fixture source = fixture("source", true, false);
        Fixture target = fixture("target", false, true);
        CanvasDefinition canvas = canvas(List.of(source.node(), target.node()),
                List.of(connection(source, target)));
        CanvasExecutionEngine engine = new CanvasExecutionEngine(Map.of(
                "source", (node, context) -> context.output(source.outputPort(), "value"),
                "target", (node, context) -> { }));

        assertThrows(IllegalArgumentException.class, () -> engine.execute(
                CanvasExecutionRequest.fromEntries(canvas, List.of(UUID.randomUUID()))));
        assertThrows(IllegalArgumentException.class, () -> engine.execute(
                CanvasExecutionRequest.fromEntries(canvas, List.of(target.id()))));
    }

    @Test
    void explicitEntryRejectsReachableNodeWithExcludedDependency() {
        Fixture excludedSource = fixture("excludedSource", true, false);
        Fixture selectedSource = fixture("selectedSource", true, false);
        Fixture merge = fixture("merge", false, true);
        UUID secondInput = UUID.randomUUID();
        CanvasNode mergeNode = new CanvasNode(merge.id(), "merge", "merge", Map.of(), List.of(
                new CanvasPort(merge.inputPort(), "left", CanvasPortDirection.INPUT),
                new CanvasPort(secondInput, "right", CanvasPortDirection.INPUT)));
        CanvasDefinition canvas = canvas(List.of(excludedSource.node(), selectedSource.node(), mergeNode),
                List.of(connection(excludedSource, merge, merge.inputPort()),
                        connection(selectedSource, merge, secondInput)));

        assertThrows(IllegalArgumentException.class, () -> new CanvasExecutionEngine(Map.of())
                .execute(CanvasExecutionRequest.fromEntries(canvas, List.of(selectedSource.id()))));
    }

    private static CanvasDefinition canvas(List<CanvasNode> nodes, List<CanvasConnection> connections) {
        return new CanvasDefinition(UUID.randomUUID(), "test", nodes, connections);
    }

    private static CanvasConnection connection(Fixture from, Fixture to) {
        return new CanvasConnection(UUID.randomUUID(), from.id(), from.outputPort(), to.id(), to.inputPort());
    }

    private static CanvasConnection connection(Fixture from, Fixture to, UUID targetInputPortId) {
        return new CanvasConnection(UUID.randomUUID(), from.id(), from.outputPort(), to.id(), targetInputPortId);
    }

    private static CanvasNode constantNode(Fixture fixture, Object value) {
        return new CanvasNode(fixture.id(), fixture.node().name(), CanvasBuiltinExecutors.CONSTANT,
                Map.of("value", value), fixture.node().ports());
    }

    private static Fixture fixture(String type, boolean output, boolean input) {
        UUID nodeId = UUID.randomUUID();
        UUID outputId = UUID.randomUUID();
        UUID inputId = UUID.randomUUID();
        List<CanvasPort> ports = new java.util.ArrayList<>();
        if (output) {
            ports.add(new CanvasPort(outputId, "out", CanvasPortDirection.OUTPUT));
        }
        if (input) {
            ports.add(new CanvasPort(inputId, "in", CanvasPortDirection.INPUT));
        }
        return new Fixture(nodeId, outputId, inputId,
                new CanvasNode(nodeId, type, type, Map.of(), ports));
    }

    private record Fixture(UUID id, UUID outputPort, UUID inputPort, CanvasNode node) {
    }
}
