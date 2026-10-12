package com.easycode.canvas.desktop;

import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.model.CanvasNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasDocumentStoreTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void savesAndReloadsEditableGraphWithStableIdentitiesAndPositions() throws IOException {
        CanvasMvpWorkspace original = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode left = original.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", new BigDecimal("20")));
        CanvasMvpWorkspace.PlacedNode right = original.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", new BigDecimal("22")));
        CanvasMvpWorkspace.PlacedNode add = original.addNode(CanvasMvpWorkspace.addNodeType(), Map.of());
        CanvasMvpWorkspace.PlacedNode print = original.addNode(CanvasMvpWorkspace.printNodeType(), Map.of());
        original.moveNode(right.node().nodeId(), 410, 260);
        original.connect(left.node().nodeId(), "out", add.node().nodeId(), "left");
        original.connect(right.node().nodeId(), "out", add.node().nodeId(), "right");
        original.connect(add.node().nodeId(), "out", print.node().nodeId(), "in");
        CanvasMvpDocument document = original.document();
        Path file = temporaryDirectory.resolve("sum.easycanvas.json");

        CanvasDocumentStore store = new CanvasDocumentStore();
        store.save(file, document);
        CanvasMvpDocument loaded = store.load(file);
        CanvasMvpWorkspace reopened = new CanvasMvpWorkspace();
        reopened.replace(loaded);

        assertEquals(document.canvasId(), reopened.definition().canvasId());
        assertEquals(document.name(), reopened.definition().name());
        assertEquals(document.nodes().stream().map(node -> node.node().nodeId()).toList(),
                reopened.nodes().stream().map(node -> node.node().nodeId()).toList());
        assertEquals(document.nodes().stream().map(node -> node.node().ports()).toList(),
                reopened.nodes().stream().map(node -> node.node().ports()).toList());
        assertEquals(document.connections(), reopened.connections());
        assertEquals(410, reopened.nodes().get(1).x());
        assertEquals(new BigDecimal("22"), new BigDecimal(
                reopened.nodes().get(1).node().configuration().get("value").toString()));
        var result = reopened.run(event -> { });
        assertEquals(List.of("42"), result.consoleOutput());
        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(0, files.filter(path -> path.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void rejectsUnsupportedNodeTypeWithoutChangingCurrentWorkspace() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode existing = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", "keep me"));
        CanvasNode unknown = new CanvasNode(UUID.randomUUID(), "unknown", "not-registered", Map.of(), List.of());
        CanvasMvpDocument invalid = new CanvasMvpDocument(CanvasMvpDocument.CURRENT_FORMAT_VERSION,
                UUID.randomUUID(), "invalid", List.of(new CanvasMvpWorkspace.PlacedNode(unknown, 24, 32)), List.of());

        assertThrows(IllegalArgumentException.class, () -> workspace.replace(invalid));
        assertEquals(List.of(existing.node().nodeId()),
                workspace.nodes().stream().map(placed -> placed.node().nodeId()).toList());
    }

    @Test
    void rejectsUnsupportedJavaValuesInsteadOfWritingAnUnreopenableFile() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        workspace.addNode(CanvasMvpWorkspace.constantNodeType(), Map.of("value", new Object()));

        assertThrows(IOException.class, () -> new CanvasDocumentStore().save(
                temporaryDirectory.resolve("unsupported.easycanvas.json"), workspace.document()));
    }

    @Test
    void refusesToSaveGraphWithMultipleIncomingConnectionsForOneInput() {
        CanvasMvpWorkspace workspace = new CanvasMvpWorkspace();
        CanvasMvpWorkspace.PlacedNode first = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", 1));
        CanvasMvpWorkspace.PlacedNode second = workspace.addNode(
                CanvasMvpWorkspace.constantNodeType(), Map.of("value", 2));
        CanvasMvpWorkspace.PlacedNode add = workspace.addNode(CanvasMvpWorkspace.addNodeType(), Map.of());
        workspace.connect(first.node().nodeId(), "out", add.node().nodeId(), "left");
        workspace.connect(second.node().nodeId(), "out", add.node().nodeId(), "left");

        assertThrows(IOException.class, () -> new CanvasDocumentStore().save(
                temporaryDirectory.resolve("invalid.easycanvas.json"), workspace.document()));
    }

    @Test
    void rejectsUnsupportedDocumentVersion() throws IOException {
        Path file = temporaryDirectory.resolve("future.easycanvas.json");
        Files.writeString(file, "{\"formatVersion\":2,\"canvasId\":\""
                + UUID.randomUUID() + "\",\"name\":\"future\",\"nodes\":[],\"connections\":[]}");

        assertThrows(IOException.class, () -> new CanvasDocumentStore().load(file));
    }
}
