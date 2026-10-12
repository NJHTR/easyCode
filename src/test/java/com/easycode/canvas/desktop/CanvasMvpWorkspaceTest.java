package com.easycode.canvas.desktop;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class CanvasMvpWorkspaceTest {
    @Test
    void revisionChangesOnlyWhenTheEditableDocumentChanges() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        assertEquals(0, workspace.revision());

        CanvasMvpWorkspace.PlacedNode constant = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", 21));
        assertEquals(1, workspace.revision());

        workspace.moveNode(constant.node().nodeId(), constant.x(), constant.y());
        assertEquals(1, workspace.revision());
        workspace.moveNode(constant.node().nodeId(), constant.x() + 20, constant.y() + 10);
        assertEquals(2, workspace.revision());

        CanvasMvpWorkspace.PlacedNode add = workspace.addNode(CanvasMvpWorkspace.addNodeType(), Map.of());
        assertEquals(3, workspace.revision());
        workspace.connect(constant.node().nodeId(), "out", add.node().nodeId(), "left");
        assertEquals(4, workspace.revision());

        CanvasMvpWorkspace reopened = new CanvasMvpWorkspace();
        reopened.replace(workspace.document());
        assertEquals(1, reopened.revision());
    }

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
