package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.execution.builtin.CanvasBuiltinNodes;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasNodeDescriptor;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasNodeCatalogIntegrationTest {
    @Test
    void builtInCatalogListsStableTypesAndCreatesExecutableNodes() {
        CanvasNodeCatalog catalog = CanvasBuiltinNodes.catalog();

        assertEquals(List.of("constant", "print", "passthrough", "add"),
                catalog.list().stream().map(CanvasNodeDescriptor::nodeType).toList());

        CanvasNode first = catalog.create(CanvasBuiltinExecutors.ADD, "first", Map.of());
        CanvasNode second = catalog.create(CanvasBuiltinExecutors.ADD, "second", Map.of());
        assertEquals(CanvasBuiltinExecutors.ADD, first.nodeType());
        assertEquals(3, first.ports().size());
        assertTrue(first.ports().stream().anyMatch(port -> port.name().equals("left")
                && port.direction() == CanvasPortDirection.INPUT));
        assertNotEquals(first.nodeId(), second.nodeId());
        assertNotEquals(first.ports().get(0).portId(), second.ports().get(0).portId());
    }

    @Test
    void createdConstantNodeRunsThroughTheExistingService() {
        CanvasNode constant = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.CONSTANT, "value", Map.of("value", 12));
        CanvasPort output = constant.ports().get(0);
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "catalog-run", List.of(constant), List.of(),
                Map.of(), Map.of("result", output.portId()));

        CanvasExecutionResult result = new CanvasService().execute(canvas);

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(12, result.namedOutputValues().get("result"));
    }

    @Test
    void unknownAndDuplicateTypesAreRejected() {
        CanvasNodeCatalog catalog = new CanvasNodeCatalog(List.of(
                new CanvasNodeDescriptor("custom", "Custom", "Custom node.", List.of())));

        assertTrue(catalog.find("custom").isPresent());
        assertTrue(catalog.find("missing").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> catalog.create("missing", "node", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new CanvasNodeCatalog(List.of(
                new CanvasNodeDescriptor("same", "One", "First.", List.of()),
                new CanvasNodeDescriptor("same", "Two", "Second.", List.of()))));
    }
}
