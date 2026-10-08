package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.execution.builtin.CanvasBuiltinLibrary;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasNodeDescriptor;
import com.easycode.canvas.model.CanvasPortDescriptor;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasNodeRegistryIntegrationTest {
    @Test
    void builtInRegistryUsesOneTypeSetForDiscoveryAndExecution() {
        CanvasNodeRegistry registry = CanvasBuiltinLibrary.registry();

        assertEquals(registry.list().stream().map(registration -> registration.descriptor().nodeType()).toList(),
                registry.catalog().list().stream().map(CanvasNodeDescriptor::nodeType).toList());
        assertEquals(registry.catalog().list().stream().map(CanvasNodeDescriptor::nodeType).toList(),
                registry.executors().keySet().stream().toList());
        assertTrue(registry.find(CanvasBuiltinExecutors.ADD).isPresent());
    }

    @Test
    void customRegistrationCanCreateAndExecuteThroughOneService() {
        CanvasNodeDescriptor descriptor = new CanvasNodeDescriptor("custom", "Custom", "Test node.",
                List.of(new CanvasPortDescriptor("out", CanvasPortDirection.OUTPUT)));
        CanvasNodeRegistry registry = new CanvasNodeRegistry(List.of(
                new CanvasNodeRegistration(descriptor, (node, context) -> context.output("out", "ok"))));
        CanvasNode node = registry.create("custom", "custom-node");
        CanvasDefinition canvas = new CanvasDefinition(java.util.UUID.randomUUID(), "custom-canvas",
                List.of(node), List.of(), Map.of(), Map.of("result", node.ports().get(0).portId()));

        CanvasExecutionResult result = new CanvasService(registry).execute(canvas);

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals("ok", result.namedOutputValues().get("result"));
    }

    @Test
    void duplicateRegistrationIsRejected() {
        CanvasNodeDescriptor descriptor = new CanvasNodeDescriptor("same", "Same", "One type.", List.of());
        CanvasNodeRegistration registration = new CanvasNodeRegistration(descriptor, (node, context) -> { });

        assertThrows(IllegalArgumentException.class,
                () -> new CanvasNodeRegistry(List.of(registration, registration)));
    }
}
