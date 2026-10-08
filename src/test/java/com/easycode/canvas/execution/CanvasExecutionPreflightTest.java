package com.easycode.canvas.execution;

import com.easycode.canvas.api.CanvasApplication;
import com.easycode.canvas.api.CanvasService;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CanvasExecutionPreflightTest {
    @Test
    void validRequestReportsPlanWithoutInvokingNodes() {
        CanvasApplication application = new CanvasApplication();
        CanvasNode constant = application.createNode(CanvasBuiltinExecutors.CONSTANT, "constant",
                Map.of("value", "ok"));
        CanvasDefinition canvas = application.newCanvas("valid")
                .addNode(constant)
                .build();

        CanvasExecutionPreflightResult result = application.preflight(canvas);

        assertTrue(result.ready());
        assertEquals(List.of(constant.nodeId()), result.plannedNodeIds());
        assertEquals(List.of(), result.diagnostics());
    }

    @Test
    void preflightNeverInvokesTheRegisteredExecutor() {
        AtomicInteger invocations = new AtomicInteger();
        UUID nodeId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "custom", "custom", Map.of(), List.of());
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "no-execution",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of("custom",
                (ignoredNode, ignoredContext) -> invocations.incrementAndGet()));

        CanvasExecutionPreflightResult result = service.preflight(canvas);

        assertTrue(result.ready());
        assertEquals(List.of(nodeId), result.plannedNodeIds());
        assertEquals(0, invocations.get());
    }

    @Test
    void missingExecutorIsReportedForOnlyTheSelectedScope() {
        UUID selectedId = UUID.randomUUID();
        UUID excludedId = UUID.randomUUID();
        CanvasNode selected = new CanvasNode(selectedId, "selected", "known", Map.of(), List.of());
        CanvasNode excluded = new CanvasNode(excludedId, "excluded", "missing", Map.of(), List.of());
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "scope",
                List.of(selected, excluded), List.of());
        CanvasService service = new CanvasService(Map.of("known", (node, context) -> { }));

        CanvasExecutionPreflightResult selectedResult = service.preflight(canvas, List.of(selectedId), Map.of());
        CanvasExecutionPreflightResult allResult = service.preflight(canvas);

        assertTrue(selectedResult.ready());
        assertFalse(allResult.ready());
        assertEquals(CanvasExecutionDiagnosticCode.MISSING_EXECUTOR,
                allResult.diagnostics().get(0).code());
        assertEquals(excludedId, allResult.diagnostics().get(0).nodeId());
    }

    @Test
    void invalidInputsAndNamedNamesBecomeStructuredDiagnostics() {
        UUID nodeId = UUID.randomUUID();
        UUID inputId = UUID.randomUUID();
        UUID outputId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "passthrough", CanvasBuiltinExecutors.PASSTHROUGH,
                Map.of(), List.of(new CanvasPort(inputId, "in", CanvasPortDirection.INPUT),
                        new CanvasPort(outputId, "out", CanvasPortDirection.OUTPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "inputs", List.of(node), List.of(),
                Map.of("value", inputId), Map.of("result", outputId));
        CanvasService service = new CanvasService();

        CanvasExecutionPreflightResult invalidPort = service.preflight(
                CanvasExecutionRequest.withInputs(canvas, List.of(nodeId), Map.of(UUID.randomUUID(), "bad")));
        CanvasExecutionPreflightResult unknownName = service.preflight(canvas, Map.of("missing", "bad"));

        assertFalse(invalidPort.ready());
        assertEquals(CanvasExecutionDiagnosticCode.INVALID_GRAPH, invalidPort.diagnostics().get(0).code());
        assertFalse(unknownName.ready());
        assertEquals(CanvasExecutionDiagnosticCode.INVALID_REQUEST, unknownName.diagnostics().get(0).code());
    }
}
