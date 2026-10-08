package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.execution.builtin.CanvasBuiltinLibrary;
import com.easycode.canvas.model.CanvasNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class CanvasApplicationIntegrationTest {
    @Test
    void defaultApplicationBuildsAndExecutesARealCanvasEndToEnd() {
        CanvasApplication application = new CanvasApplication();
        CanvasNode source = application.createNode(CanvasBuiltinExecutors.CONSTANT, "source",
                Map.of("value", "application-ok"));
        CanvasNode print = application.createNode(CanvasBuiltinExecutors.PRINT, "print");

        var canvas = application.newCanvas("application-canvas")
                .addNode(source)
                .addNode(print)
                .connect(source.nodeId(), "out", print.nodeId(), "in")
                .build();

        CanvasExecutionResult result = application.execute(canvas);

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of("application-ok"), result.consoleOutput());
    }

    @Test
    void applicationUsesTheSameRegistryForCreationAndExecution() {
        CanvasNodeRegistry registry = CanvasBuiltinLibrary.registry();
        CanvasApplication application = new CanvasApplication(registry);

        assertSame(registry, application.nodes());
        assertEquals(registry.executors().keySet(), application.nodes().catalog().list().stream()
                .map(descriptor -> descriptor.nodeType()).collect(java.util.stream.Collectors.toSet()));
    }
}
