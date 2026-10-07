package com.easycode.canvas.integration;

import com.easycode.canvas.exception.CanvasValidationException;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import com.easycode.canvas.validation.CanvasGraphValidator;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CanvasGraphIntegrationTest {
    @Test
    void validatesAConnectedCanvasGraph() {
        UUID sourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        UUID outputPortId = UUID.randomUUID();
        UUID inputPortId = UUID.randomUUID();
        CanvasDefinition canvas = new CanvasDefinition(
                UUID.randomUUID(),
                "main",
                List.of(
                        new CanvasNode(sourceNodeId, "source", "value", Map.of("value", 1),
                                List.of(new CanvasPort(outputPortId, "out", CanvasPortDirection.OUTPUT))),
                        new CanvasNode(targetNodeId, "target", "print", Map.of(),
                                List.of(new CanvasPort(inputPortId, "in", CanvasPortDirection.INPUT)))),
                List.of(new CanvasConnection(UUID.randomUUID(), sourceNodeId, outputPortId,
                        targetNodeId, inputPortId)));

        assertDoesNotThrow(() -> new CanvasGraphValidator().validate(canvas));
        assertEquals(2, canvas.nodes().size());
        assertEquals("value", canvas.nodes().get(0).nodeType());
    }

    @Test
    void rejectsConnectionWithWrongPortDirection() {
        UUID firstNodeId = UUID.randomUUID();
        UUID secondNodeId = UUID.randomUUID();
        UUID firstPortId = UUID.randomUUID();
        UUID secondPortId = UUID.randomUUID();
        CanvasDefinition canvas = new CanvasDefinition(
                UUID.randomUUID(), "invalid", List.of(
                        new CanvasNode(firstNodeId, "first", "a", Map.of(),
                                List.of(new CanvasPort(firstPortId, "in", CanvasPortDirection.INPUT))),
                        new CanvasNode(secondNodeId, "second", "b", Map.of(),
                                List.of(new CanvasPort(secondPortId, "out", CanvasPortDirection.OUTPUT)))),
                List.of(new CanvasConnection(UUID.randomUUID(), firstNodeId, firstPortId,
                        secondNodeId, secondPortId)));

        assertThrows(CanvasValidationException.class,
                () -> new CanvasGraphValidator().validate(canvas));
    }

    @Test
    void rejectsDuplicateNodeAndPortIdentities() {
        UUID nodeId = UUID.randomUUID();
        UUID portId = UUID.randomUUID();
        CanvasDefinition canvas = new CanvasDefinition(
                UUID.randomUUID(), "duplicate", List.of(
                        new CanvasNode(nodeId, "one", "value", Map.of(),
                                List.of(new CanvasPort(portId, "out", CanvasPortDirection.OUTPUT))),
                        new CanvasNode(nodeId, "two", "print", Map.of(),
                                List.of(new CanvasPort(portId, "in", CanvasPortDirection.INPUT)))),
                List.of());

        assertThrows(CanvasValidationException.class,
                () -> new CanvasGraphValidator().validate(canvas));
    }

    @Test
    void rejectsMultipleConnectionsDrivingTheSameInputPort() {
        UUID firstSourceNodeId = UUID.randomUUID();
        UUID secondSourceNodeId = UUID.randomUUID();
        UUID targetNodeId = UUID.randomUUID();
        UUID firstOutputPortId = UUID.randomUUID();
        UUID secondOutputPortId = UUID.randomUUID();
        UUID inputPortId = UUID.randomUUID();
        CanvasDefinition canvas = new CanvasDefinition(
                UUID.randomUUID(), "multiple-input-drivers", List.of(
                        new CanvasNode(firstSourceNodeId, "first", "value", Map.of(),
                                List.of(new CanvasPort(firstOutputPortId, "out", CanvasPortDirection.OUTPUT))),
                        new CanvasNode(secondSourceNodeId, "second", "value", Map.of(),
                                List.of(new CanvasPort(secondOutputPortId, "out", CanvasPortDirection.OUTPUT))),
                        new CanvasNode(targetNodeId, "target", "print", Map.of(),
                                List.of(new CanvasPort(inputPortId, "in", CanvasPortDirection.INPUT)))),
                List.of(
                        new CanvasConnection(UUID.randomUUID(), firstSourceNodeId, firstOutputPortId,
                                targetNodeId, inputPortId),
                        new CanvasConnection(UUID.randomUUID(), secondSourceNodeId, secondOutputPortId,
                                targetNodeId, inputPortId)));

        assertThrows(CanvasValidationException.class,
                () -> new CanvasGraphValidator().validate(canvas));
    }

    @Test
    void canvasCollectionsAreImmutable() {
        CanvasDefinition canvas = CanvasDefinition.empty(UUID.randomUUID(), "empty");

        assertThrows(UnsupportedOperationException.class,
                () -> canvas.nodes().add(null));
    }
}
