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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class CanvasExecutionIdentityTest {
    @Test
    void explicitExecutionIdIsPreservedByPreflightAndSuccess() {
        CanvasApplication application = new CanvasApplication();
        CanvasNode constant = application.createNode(CanvasBuiltinExecutors.CONSTANT, "constant",
                Map.of("value", "ok"));
        CanvasDefinition canvas = application.newCanvas("identity")
                .addNode(constant)
                .build();
        UUID executionId = UUID.randomUUID();
        CanvasExecutionRequest request = CanvasExecutionRequest.forCanvas(executionId, canvas);

        CanvasExecutionPreflightResult preflight = application.preflight(request);
        CanvasExecutionResult result = application.execute(request);

        assertEquals(executionId, preflight.executionId());
        assertEquals(executionId, result.executionId());
        assertEquals(canvas.canvasId(), result.canvasId());
    }

    @Test
    void explicitExecutionIdIsPreservedWhenANodeFails() {
        UUID nodeId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "broken", "broken", Map.of(), List.of());
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "failed-identity",
                List.of(node), List.of());
        CanvasService service = new CanvasService(Map.of(
                "broken", (ignoredNode, ignoredContext) -> {
                    throw new IllegalStateException("expected failure");
                }));
        UUID executionId = UUID.randomUUID();

        CanvasExecutionResult result = service.execute(
                new CanvasExecutionRequest(executionId, canvas, List.of(), Map.of()));

        assertEquals(executionId, result.executionId());
        assertEquals(CanvasExecutionStatus.FAILED, result.status());
        assertEquals(nodeId, result.failedNodeId());
    }

    @Test
    void explicitExecutionIdIsPreservedByAnInvalidPreflight() {
        UUID nodeId = UUID.randomUUID();
        UUID inputPortId = UUID.randomUUID();
        CanvasNode node = new CanvasNode(nodeId, "input", "input", Map.of(), List.of(
                new CanvasPort(inputPortId, "in", CanvasPortDirection.INPUT)));
        CanvasDefinition canvas = new CanvasDefinition(UUID.randomUUID(), "invalid-input",
                List.of(node), List.of());
        UUID executionId = UUID.randomUUID();
        CanvasExecutionRequest request = CanvasExecutionRequest.withInputs(executionId, canvas,
                List.of(nodeId), Map.of(UUID.randomUUID(), "bad"));

        CanvasExecutionPreflightResult result = new CanvasService(Map.of()).preflight(request);

        assertEquals(executionId, result.executionId());
        assertEquals(CanvasExecutionDiagnosticCode.INVALID_GRAPH, result.diagnostics().get(0).code());
    }

    @Test
    void convenienceRunsReceiveDistinctExecutionIds() {
        CanvasApplication application = new CanvasApplication();
        CanvasDefinition canvas = application.newCanvas("repeated")
                .addNode(application.createNode(CanvasBuiltinExecutors.CONSTANT, "constant",
                        Map.of("value", "ok")))
                .build();

        CanvasExecutionResult first = application.execute(canvas);
        CanvasExecutionResult second = application.execute(canvas);

        assertNotNull(first.executionId());
        assertNotNull(second.executionId());
        assertNotEquals(first.executionId(), second.executionId());
    }
}
