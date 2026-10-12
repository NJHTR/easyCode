package com.easycode.canvas.desktop;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasMvpWorkspaceTest {
    @Test
    void userComposedGraphExecutesThroughCanvasApplication() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode constant = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", new BigDecimal("21")));
        CanvasMvpWorkspace.PlacedNode add = workspace.addNode(CanvasMvpWorkspace.addNodeType(), Map.of());
        CanvasMvpWorkspace.PlacedNode print = workspace.addNode(CanvasMvpWorkspace.printNodeType(), Map.of());
        workspace.connect(constant.node().nodeId(), "out", add.node().nodeId(), "left");
        workspace.connect(constant.node().nodeId(), "out", add.node().nodeId(), "right");
        workspace.connect(add.node().nodeId(), "out", print.node().nodeId(), "in");
        var originalCanvasId = workspace.definition().canvasId();

        CanvasExecutionResult result = workspace.run(event -> { });

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(originalCanvasId, workspace.definition().canvasId());
        assertEquals(3, result.completedNodeIds().size());
        assertEquals(new BigDecimal("42"), result.nodeTraces().get(1).outputs().values().iterator().next());
        assertEquals(java.util.List.of("42"), result.consoleOutput());
    }
}
