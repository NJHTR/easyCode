package com.easycode.canvas.desktop;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void editingConstantValuePreservesNodeAndPortIdentitiesAndChangesExecution() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode constant = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", new BigDecimal("21")));
        CanvasMvpWorkspace.PlacedNode add = workspace.addNode(CanvasMvpWorkspace.addNodeType(), Map.of());
        CanvasMvpWorkspace.PlacedNode print = workspace.addNode(CanvasMvpWorkspace.printNodeType(), Map.of());
        workspace.connect(constant.node().nodeId(), "out", add.node().nodeId(), "left");
        workspace.connect(constant.node().nodeId(), "out", add.node().nodeId(), "right");
        workspace.connect(add.node().nodeId(), "out", print.node().nodeId(), "in");
        long revisionBeforeEdit = workspace.revision();

        CanvasMvpWorkspace.PlacedNode edited = workspace.updateNodeConfiguration(
                constant.node().nodeId(), Map.of("value", new BigDecimal("22")));

        assertEquals(constant.node().nodeId(), edited.node().nodeId());
        assertEquals(constant.node().ports(), edited.node().ports());
        assertEquals(revisionBeforeEdit + 1, workspace.revision());
        assertEquals(List.of("44"), workspace.run(event -> { }).consoleOutput());
    }

    @Test
    void deletingNodeRemovesIncidentConnectionsAndRemainingGraphStillExecutes() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode constant = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", 7));
        CanvasMvpWorkspace.PlacedNode passthrough = workspace.addNode(
                "passthrough", Map.of());
        CanvasMvpWorkspace.PlacedNode print = workspace.addNode(CanvasMvpWorkspace.printNodeType(), Map.of());
        workspace.connect(constant.node().nodeId(), "out", passthrough.node().nodeId(), "in");
        workspace.connect(passthrough.node().nodeId(), "out", print.node().nodeId(), "in");

        List<com.easycode.canvas.model.CanvasConnection> removed = workspace.deleteNode(
                print.node().nodeId());

        assertEquals(1, removed.size());
        assertEquals(1, workspace.connections().size());
        assertEquals(CanvasExecutionStatus.SUCCEEDED, workspace.run(event -> { }).status());
    }

    @Test
    void deletingConnectionChangesTheGraphThatWillActuallyRun() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode constant = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", 7));
        CanvasMvpWorkspace.PlacedNode print = workspace.addNode(CanvasMvpWorkspace.printNodeType(), Map.of());
        var connection = workspace.connect(constant.node().nodeId(), "out", print.node().nodeId(), "in");

        assertTrue(workspace.deleteConnection(connection.connectionId()));
        assertEquals(List.of(), workspace.connections());
        assertEquals(CanvasExecutionStatus.FAILED, workspace.run(event -> { }).status());
    }
}
