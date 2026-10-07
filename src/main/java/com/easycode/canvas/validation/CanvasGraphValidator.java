package com.easycode.canvas.validation;

import com.easycode.canvas.exception.CanvasValidationException;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates graph-wide identity and connection invariants. */
public final class CanvasGraphValidator {
    public void validate(CanvasDefinition canvas) {
        Objects.requireNonNull(canvas, "canvas");
        Set<UUID> nodeIds = new HashSet<>();
        Map<UUID, CanvasPort> ports = new HashMap<>();
        Map<UUID, UUID> portOwners = new HashMap<>();
        for (CanvasNode node : canvas.nodes()) {
            if (!nodeIds.add(node.nodeId())) {
                throw invalid("duplicate node id: " + node.nodeId());
            }
            Set<String> portNames = new HashSet<>();
            for (CanvasPort port : node.ports()) {
                if (ports.putIfAbsent(port.portId(), port) != null) {
                    throw invalid("duplicate port id: " + port.portId());
                }
                portOwners.put(port.portId(), node.nodeId());
                if (!portNames.add(port.name())) {
                    throw invalid("duplicate port name on node " + node.nodeId() + ": " + port.name());
                }
            }
        }

        Set<UUID> connectionIds = new HashSet<>();
        Set<ConnectionKey> connectionKeys = new HashSet<>();
        Set<UUID> drivenInputPorts = new HashSet<>();
        for (CanvasConnection connection : canvas.connections()) {
            if (!connectionIds.add(connection.connectionId())) {
                throw invalid("duplicate connection id: " + connection.connectionId());
            }
            if (!nodeIds.contains(connection.fromNodeId()) || !nodeIds.contains(connection.toNodeId())) {
                throw invalid("connection references an unknown node: " + connection.connectionId());
            }
            CanvasPort fromPort = requirePort(ports, connection.fromPortId(), connection.connectionId());
            CanvasPort toPort = requirePort(ports, connection.toPortId(), connection.connectionId());
            if (!connection.fromNodeId().equals(portOwners.get(connection.fromPortId()))
                    || !connection.toNodeId().equals(portOwners.get(connection.toPortId()))) {
                throw invalid("connection endpoint does not belong to its declared node: "
                        + connection.connectionId());
            }
            if (fromPort.direction() != CanvasPortDirection.OUTPUT
                    || toPort.direction() != CanvasPortDirection.INPUT) {
                throw invalid("connection must go from an output port to an input port: "
                        + connection.connectionId());
            }
            if (!drivenInputPorts.add(connection.toPortId())) {
                throw invalid("input port cannot have multiple incoming connections: "
                        + connection.toPortId());
            }
            if (!connectionKeys.add(new ConnectionKey(
                    connection.fromNodeId(), connection.fromPortId(),
                    connection.toNodeId(), connection.toPortId()))) {
                throw invalid("duplicate connection endpoints: " + connection.connectionId());
            }
        }
    }

    private static CanvasPort requirePort(Map<UUID, CanvasPort> ports, UUID portId, UUID connectionId) {
        CanvasPort port = ports.get(portId);
        if (port == null) {
            throw invalid("connection references an unknown port: " + connectionId);
        }
        return port;
    }

    private static CanvasValidationException invalid(String message) {
        return new CanvasValidationException(message);
    }

    private record ConnectionKey(UUID fromNodeId, UUID fromPortId, UUID toNodeId, UUID toPortId) {
    }
}
