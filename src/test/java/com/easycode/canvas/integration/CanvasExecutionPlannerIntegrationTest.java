package com.easycode.canvas.integration;

import com.easycode.canvas.exception.CanvasValidationException;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import com.easycode.canvas.plan.CanvasExecutionPlan;
import com.easycode.canvas.plan.CanvasExecutionPlanner;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasExecutionPlannerIntegrationTest {
    private final CanvasExecutionPlanner planner = new CanvasExecutionPlanner();

    @Test
    void plansLinearGraphInDependencyOrder() {
        NodeFixture a = node("a", true, false);
        NodeFixture b = node("b", true, true);
        NodeFixture c = node("c", false, true);
        CanvasDefinition canvas = canvas(List.of(a.node(), b.node(), c.node()), List.of(
                connection(a, b), connection(b, c)));

        CanvasExecutionPlan plan = planner.plan(canvas);

        assertEquals(List.of(a.id(), b.id(), c.id()), plan.orderedNodeIds());
    }

    @Test
    void keepsDeclarationOrderForIndependentNodes() {
        NodeFixture first = node("first", false, false);
        NodeFixture second = node("second", false, false);

        assertEquals(List.of(first.id(), second.id()),
                planner.plan(canvas(List.of(first.node(), second.node()), List.of())).orderedNodeIds());
    }

    @Test
    void respectsBranchAndMergeDependencies() {
        NodeFixture source = node("source", true, false);
        NodeFixture left = node("left", true, true);
        NodeFixture right = node("right", true, true);
        UUID mergeNodeId = UUID.randomUUID();
        UUID mergeLeftInputId = UUID.randomUUID();
        UUID mergeRightInputId = UUID.randomUUID();
        NodeFixture merge = new NodeFixture(mergeNodeId, UUID.randomUUID(), mergeLeftInputId,
                new CanvasNode(mergeNodeId, "merge", "test", Map.of(), List.of(
                        new CanvasPort(mergeLeftInputId, "left", CanvasPortDirection.INPUT),
                        new CanvasPort(mergeRightInputId, "right", CanvasPortDirection.INPUT))));
        CanvasExecutionPlan plan = planner.plan(canvas(
                List.of(source.node(), left.node(), right.node(), merge.node()),
                List.of(connection(source, left), connection(source, right), connection(left, merge),
                        connection(right, merge, mergeRightInputId))));

        assertEquals(source.id(), plan.orderedNodeIds().get(0));
        assertEquals(merge.id(), plan.orderedNodeIds().get(3));
    }

    @Test
    void rejectsCycles() {
        NodeFixture first = node("first", true, true);
        NodeFixture second = node("second", true, true);

        assertThrows(CanvasValidationException.class,
                () -> planner.plan(canvas(List.of(first.node(), second.node()),
                        List.of(connection(first, second), connection(second, first)))));
    }

    @Test
    void rejectsStructurallyInvalidGraphBeforePlanning() {
        NodeFixture source = node("source", true, false);
        NodeFixture target = node("target", false, true);
        CanvasConnection connection = connection(source, target);
        CanvasConnection duplicate = new CanvasConnection(UUID.randomUUID(), source.id(), source.outputPortId(),
                target.id(), target.inputPortId());

        assertThrows(CanvasValidationException.class,
                () -> planner.plan(canvas(List.of(source.node(), target.node()), List.of(connection, duplicate))));
    }

    @Test
    void returnsImmutablePlan() {
        NodeFixture only = node("only", false, false);
        CanvasExecutionPlan plan = planner.plan(canvas(List.of(only.node()), List.of()));

        assertThrows(UnsupportedOperationException.class,
                () -> plan.orderedNodeIds().add(UUID.randomUUID()));
    }

    @Test
    void plansEmptyCanvas() {
        CanvasDefinition canvas = CanvasDefinition.empty(UUID.randomUUID(), "empty");

        assertEquals(List.of(), planner.plan(canvas).orderedNodeIds());
    }

    private static CanvasDefinition canvas(List<CanvasNode> nodes, List<CanvasConnection> connections) {
        return new CanvasDefinition(UUID.randomUUID(), "test", nodes, connections);
    }

    private static NodeFixture node(String name, boolean output, boolean input) {
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
        return new NodeFixture(nodeId, outputId, inputId,
                new CanvasNode(nodeId, name, "test", Map.of(), ports));
    }

    private static CanvasConnection connection(NodeFixture from, NodeFixture to) {
        return new CanvasConnection(UUID.randomUUID(), from.id(), from.outputPortId(), to.id(), to.inputPortId());
    }

    private static CanvasConnection connection(NodeFixture from, NodeFixture to, UUID targetInputPortId) {
        return new CanvasConnection(UUID.randomUUID(), from.id(), from.outputPortId(),
                to.id(), targetInputPortId);
    }

    private record NodeFixture(UUID id, UUID outputPortId, UUID inputPortId, CanvasNode node) {
    }
}
