package com.easycode.canvas.api;

import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.execution.builtin.CanvasBuiltinNodes;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasBuilderIntegrationTest {
    @Test
    void buildsAndExecutesAConnectedCanvasUsingPortNames() {
        CanvasNode source = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.CONSTANT, "source", Map.of("value", "hello"));
        CanvasNode passthrough = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.PASSTHROUGH, "middle");
        CanvasNode print = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.PRINT, "console");

        CanvasDefinition canvas = new CanvasBuilder("port-name-graph")
                .addNode(source)
                .addNode(passthrough)
                .addNode(print)
                .connect(source.nodeId(), "out", passthrough.nodeId(), "in")
                .connect(passthrough.nodeId(), "out", print.nodeId(), "in")
                .bindOutput("result", passthrough.nodeId(), "out")
                .build();

        CanvasExecutionResult result = new CanvasService().execute(canvas);

        assertEquals(CanvasExecutionStatus.SUCCEEDED, result.status());
        assertEquals(List.of("hello"), result.consoleOutput());
        assertEquals("hello", result.namedOutputValues().get("result"));
    }

    @Test
    void builderResolvesInputsAndRejectsInvalidReferences() {
        CanvasNode passthrough = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.PASSTHROUGH, "value");
        CanvasBuilder builder = new CanvasBuilder(UUID.randomUUID(), "inputs")
                .addNode(passthrough)
                .bindInput("value", passthrough.nodeId(), "in")
                .bindOutput("result", passthrough.nodeId(), "out");

        CanvasDefinition canvas = builder.build();
        assertEquals(passthrough.ports().get(0).portId(), canvas.inputBindings().get("value"));
        assertThrows(IllegalArgumentException.class,
                () -> new CanvasBuilder("missing").bindOutput("out", UUID.randomUUID(), "out"));
        assertThrows(IllegalArgumentException.class,
                () -> builder.bindInput("value", passthrough.nodeId(), "in"));
    }

    @Test
    void builtDefinitionIsIndependentFromBuilderState() {
        CanvasNode constant = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.CONSTANT, "constant", Map.of("value", 1));
        CanvasBuilder builder = new CanvasBuilder("immutable").addNode(constant);

        CanvasDefinition first = builder.build();
        CanvasNode second = CanvasBuiltinNodes.catalog()
                .create(CanvasBuiltinExecutors.CONSTANT, "second", Map.of("value", 2));
        builder.addNode(second);

        assertEquals(1, first.nodes().size());
        assertEquals(2, builder.build().nodes().size());
    }
}
