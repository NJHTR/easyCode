package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionRequest;
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

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasServiceIntegrationTest {
    @Test
    void defaultServiceExecutesBuiltInNodesWithNamedInputsAndOutputs() {
        UUID nodeId = UUID.randomUUID();
        UUID leftPortId = UUID.randomUUID();
        UUID rightPortId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        CanvasNode add = new CanvasNode(nodeId, "add", CanvasBuiltinExecutors.ADD, Map.of(), List.of(
                new CanvasPort(leftPortId, "left", CanvasPortDirection.INPUT),
                new CanvasPort(rightPortId, "right", CanvasPortDirection.INPUT),
                new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "calculator", List.of(add), List.of(),
                Map.of("left", leftPortId, "right", rightPortId), Map.of("sum", outputPortId));

        CanvasExecutionResult result = new CanvasService().execute(canvas, Map.of("left", 12, "right", 8));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(0, new BigDecimal("20")
                .compareTo((BigDecimal) result.namedOutputValues().get("sum")));
    }

    @Test
    void serviceCanRunOnlyTheSelectedEntryBranch() {
        NodePorts selected = constant("selected", "chosen");
        NodePorts excluded = constant("excluded", "ignored");
        UUID printNodeId = UUID.randomUUID();
        UUID printInputId = UUID.randomUUID();
        CanvasNode print = new CanvasNode(printNodeId, "print", CanvasBuiltinExecutors.PRINT, Map.of(),
                List.of(new CanvasPort(printInputId, "in", CanvasPortDirection.INPUT)));
        CanvasConnection connection = new CanvasConnection(UUID.randomUUID(), selected.node().nodeId(),
                selected.outputPortId(), printNodeId, printInputId);
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "selected-branch",
                List.of(selected.node(), print, excluded.node()), List.of(connection));

        CanvasExecutionResult result = new CanvasService().execute(canvas,
                List.of(selected.node().nodeId()), Map.of());

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of(selected.node().nodeId(), printNodeId), result.completedNodeIds());
        assertEquals(List.of("chosen"), result.consoleOutput());
    }

    @Test
    void serviceAcceptsCustomNodeExecutorsAndPreparedRequests() {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        CanvasNode custom = new CanvasNode(nodeId, "custom", "custom", Map.of(),
                List.of(new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "custom-canvas", List.of(custom),
                List.of(), Map.of(), Map.of("result", outputPortId));
        CanvasService service = new CanvasService(Map.of(
                "custom", (node, context) -> context.output("out", "custom-ok")));

        CanvasExecutionResult result = service.execute(CanvasExecutionRequest.forCanvas(canvas));

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals("custom-ok", result.namedOutputValues().get("result"));
    }

    private static NodePorts constant(String name, Object value) {
        UUID nodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, name, CanvasBuiltinExecutors.CONSTANT, Map.of("value", value),
                List.of(new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT)));
        return new NodePorts(node, outputPortId);
    }

    private record NodePorts(CanvasNode node, UUID outputPortId) {
    }
}
