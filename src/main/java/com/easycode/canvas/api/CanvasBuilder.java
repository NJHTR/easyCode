package com.easycode.canvas.api;

import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDirection;
import com.easycode.canvas.validation.CanvasGraphValidator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable assembly helper that produces one validated immutable canvas definition. */
public final class CanvasBuilder {
    private final UUID canvasId;
    private final String name;
    private final Map<UUID, CanvasNode> nodes = new LinkedHashMap<>();
    private final List<CanvasConnection> connections = new ArrayList<>();
    private final Map<String, UUID> inputBindings = new LinkedHashMap<>();
    private final Map<String, UUID> outputBindings = new LinkedHashMap<>();

    public CanvasBuilder(String name) {
        this(UUID.randomUUID(), name);
    }

    public CanvasBuilder(UUID canvasId, String name) {
        this.canvasId = Objects.requireNonNull(canvasId, "canvasId");
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("canvas name must not be blank");
        }
        this.name = name;
    }

    public CanvasBuilder addNode(CanvasNode node) {
        Objects.requireNonNull(node, "node");
        if (nodes.putIfAbsent(node.nodeId(), node) != null) {
            throw new IllegalArgumentException("node already added: " + node.nodeId());
        }
        return this;
    }

    public CanvasBuilder connect(UUID fromNodeId, String fromPortName,
                                 UUID toNodeId, String toPortName) {
        CanvasPort fromPort = requirePort(fromNodeId, fromPortName, CanvasPortDirection.OUTPUT);
        CanvasPort toPort = requirePort(toNodeId, toPortName, CanvasPortDirection.INPUT);
        connections.add(new CanvasConnection(UUID.randomUUID(), fromNodeId, fromPort.portId(),
                toNodeId, toPort.portId()));
        return this;
    }

    public CanvasBuilder bindInput(String name, UUID nodeId, String portName) {
        CanvasPort port = requirePort(nodeId, portName, CanvasPortDirection.INPUT);
        putBinding(inputBindings, name, port.portId(), "input");
        return this;
    }

    public CanvasBuilder bindOutput(String name, UUID nodeId, String portName) {
        CanvasPort port = requirePort(nodeId, portName, CanvasPortDirection.OUTPUT);
        putBinding(outputBindings, name, port.portId(), "output");
        return this;
    }

    /** Builds an immutable definition and validates all graph-wide invariants. */
    public CanvasDefinition build() {
        CanvasDefinition canvas = new CanvasDefinition(canvasId, name,
                List.copyOf(nodes.values()), List.copyOf(connections),
                Collections.unmodifiableMap(new LinkedHashMap<>(inputBindings)),
                Collections.unmodifiableMap(new LinkedHashMap<>(outputBindings)));
        new CanvasGraphValidator().validate(canvas);
        return canvas;
    }

    private CanvasPort requirePort(UUID nodeId, String portName, CanvasPortDirection direction) {
        Objects.requireNonNull(nodeId, "nodeId");
        if (portName == null || portName.isBlank()) {
            throw new IllegalArgumentException("port name must not be blank");
        }
        CanvasNode node = nodes.get(nodeId);
        if (node == null) {
            throw new IllegalArgumentException("node has not been added: " + nodeId);
        }
        CanvasPort match = null;
        for (CanvasPort port : node.ports()) {
            if (port.direction() == direction && port.name().equals(portName)) {
                if (match != null) {
                    throw new IllegalArgumentException("node has multiple ports named: " + portName);
                }
                match = port;
            }
        }
        if (match == null) {
            throw new IllegalArgumentException("node has no " + direction.name().toLowerCase()
                    + " port named: " + portName);
        }
        return match;
    }

    private static void putBinding(Map<String, UUID> bindings, String name, UUID portId, String kind) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(kind + " binding name must not be blank");
        }
        if (bindings.putIfAbsent(name, portId) != null) {
            throw new IllegalArgumentException("duplicate " + kind + " binding name: " + name);
        }
    }
}
