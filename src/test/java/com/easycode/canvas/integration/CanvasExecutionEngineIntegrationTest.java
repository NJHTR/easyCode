package com.easycode.canvas.integration;

import com.easycode.canvas.execution.CanvasExecutionEngine;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
                canvas(List.of(leftNode, rightNode, addNode, printNode), List.of(
                        connection(left, add, add.inputPort()),
                        connection(right, add, addRightInputId),
                        connection(add, print))));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(new BigDecimal("3.25"), result.nodeTraces().get(2).outputs().get(add.outputPort()));
        assertEquals(List.of("3.25"), result.consoleOutput());
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
