package com.easycode.canvas.desktop;

import com.easycode.canvas.api.CanvasApplication;
import com.easycode.canvas.execution.CanvasExecutionRequest;
import com.easycode.canvas.execution.CanvasExecutionResult;
import com.easycode.canvas.execution.CanvasExecutionObserver;
import com.easycode.canvas.execution.CanvasExecutionStatus;
import com.easycode.canvas.execution.builtin.CanvasBuiltinExecutors;
import com.easycode.canvas.model.CanvasConnection;
import com.easycode.canvas.model.CanvasDefinition;
import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPort;
import com.easycode.canvas.model.CanvasPortDescriptor;
import com.easycode.canvas.model.CanvasPortDirection;
import com.easycode.canvas.validation.CanvasGraphValidator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable document state for the first local canvas editor. */
public final class CanvasMvpWorkspace {
    private final CanvasApplication application;
    private UUID canvasId = UUID.randomUUID();
    private String name;
    private final Map<UUID, PlacedNode> nodes = new LinkedHashMap<>();
    private final List<CanvasConnection> connections = new ArrayList<>();
    private int nextNodeNumber = 1;
    private long revision;

    public CanvasMvpWorkspace() {
        this("My first canvas", new CanvasApplication());
    }

    CanvasMvpWorkspace(String name, CanvasApplication application) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("canvas name must not be blank");
        }
        this.name = name;
        this.application = Objects.requireNonNull(application, "application");
    }

    public synchronized PlacedNode addNode(String nodeType, Map<String, Object> configuration) {
        String displayName = application.nodes().find(nodeType)
                .orElseThrow(() -> new IllegalArgumentException("unknown node type: " + nodeType))
                .descriptor().displayName();
        Map<String, Object> safeConfiguration = configuration == null ? Map.of() : configuration;
        CanvasNode node = application.createNode(nodeType, displayName + " " + nextNodeNumber++, safeConfiguration);
        PlacedNode placed = new PlacedNode(node, 48 + (nodes.size() % 3) * 270,
                48 + (nodes.size() / 3) * 190);
        nodes.put(node.nodeId(), placed);
        revision++;
        return placed;
    }

    public synchronized void moveNode(UUID nodeId, int x, int y) {
        PlacedNode current = requireNode(nodeId);
        PlacedNode moved = new PlacedNode(current.node(), Math.max(8, x), Math.max(8, y));
        if (current.x() != moved.x() || current.y() != moved.y()) {
            nodes.put(nodeId, moved);
            revision++;
        }
    }

    public synchronized CanvasConnection connect(UUID fromNodeId, String fromPortName,
                                                 UUID toNodeId, String toPortName) {
        CanvasPort from = requirePort(fromNodeId, fromPortName, CanvasPortDirection.OUTPUT);
        CanvasPort to = requirePort(toNodeId, toPortName, CanvasPortDirection.INPUT);
        CanvasConnection connection = new CanvasConnection(UUID.randomUUID(), fromNodeId,
                from.portId(), toNodeId, to.portId());
        connections.add(connection);
        revision++;
        return connection;
    }

    public synchronized PlacedNode updateNodeConfiguration(UUID nodeId, Map<String, Object> configuration) {
        PlacedNode current = requireNode(nodeId);
        Map<String, Object> safeConfiguration = configuration == null ? Map.of() : configuration;
        if (current.node().configuration().equals(safeConfiguration)) {
            return current;
        }
        CanvasNode updated = new CanvasNode(current.node().nodeId(), current.node().name(),
                current.node().nodeType(), safeConfiguration, current.node().ports());
        PlacedNode placed = new PlacedNode(updated, current.x(), current.y());
        nodes.put(nodeId, placed);
        revision++;
        return placed;
    }

    public synchronized List<CanvasConnection> deleteNode(UUID nodeId) {
        requireNode(nodeId);
        nodes.remove(nodeId);
        List<CanvasConnection> removedConnections = connections.stream()
                .filter(connection -> connection.fromNodeId().equals(nodeId)
                        || connection.toNodeId().equals(nodeId))
                .toList();
        connections.removeAll(removedConnections);
        revision++;
        return removedConnections;
    }

    public synchronized boolean deleteConnection(UUID connectionId) {
        Objects.requireNonNull(connectionId, "connectionId");
        boolean removed = connections.removeIf(connection -> connection.connectionId().equals(connectionId));
        if (removed) {
            revision++;
        }
        return removed;
    }

    public synchronized CanvasDefinition definition() {
        return new CanvasDefinition(canvasId, name,
                nodes.values().stream().map(PlacedNode::node).toList(), List.copyOf(connections));
    }

    public synchronized CanvasMvpDocument document() {
        return new CanvasMvpDocument(CanvasMvpDocument.CURRENT_FORMAT_VERSION, canvasId, name,
                List.copyOf(nodes.values()), List.copyOf(connections));
    }

    /** Validates a complete document before replacing the current workspace state. */
    public synchronized void replace(CanvasMvpDocument document) {
        Objects.requireNonNull(document, "document");
        Map<UUID, PlacedNode> loadedNodes = new LinkedHashMap<>();
        for (PlacedNode placed : document.nodes()) {
            CanvasNode node = placed.node();
            var registration = application.nodes().find(node.nodeType())
                    .orElseThrow(() -> new IllegalArgumentException(
                            "unknown node type in canvas file: " + node.nodeType()));
            List<CanvasPortDescriptor> actualPorts = node.ports().stream()
                    .map(port -> new CanvasPortDescriptor(port.name(), port.direction()))
                    .toList();
            if (!registration.descriptor().ports().equals(actualPorts)) {
                throw new IllegalArgumentException("node ports do not match the registered type: " + node.nodeType());
            }
            if (loadedNodes.putIfAbsent(node.nodeId(), placed) != null) {
                throw new IllegalArgumentException("duplicate node id in canvas file: " + node.nodeId());
            }
        }
        CanvasDefinition candidate = new CanvasDefinition(document.canvasId(), document.name(),
                document.nodes().stream().map(PlacedNode::node).toList(), document.connections());
        new CanvasGraphValidator().validate(candidate);

        nodes.clear();
        nodes.putAll(loadedNodes);
        connections.clear();
        connections.addAll(document.connections());
        canvasId = document.canvasId();
        name = document.name();
        nextNodeNumber = nodes.size() + 1;
        revision++;
    }

    public CanvasExecutionResult run(CanvasExecutionObserver observer) {
        return application.execute(CanvasExecutionRequest.forCanvas(definition()), observer);
    }

    public synchronized List<PlacedNode> nodes() {
        return List.copyOf(nodes.values());
    }

    public synchronized List<CanvasConnection> connections() {
        return List.copyOf(connections);
    }

    public synchronized long revision() {
        return revision;
    }

    private PlacedNode requireNode(UUID nodeId) {
        PlacedNode node = nodes.get(Objects.requireNonNull(nodeId, "nodeId"));
        if (node == null) {
            throw new IllegalArgumentException("unknown node: " + nodeId);
        }
        return node;
    }

    private CanvasPort requirePort(UUID nodeId, String portName, CanvasPortDirection direction) {
        CanvasNode node = requireNode(nodeId).node();
        return node.ports().stream()
                .filter(port -> port.name().equals(portName) && port.direction() == direction)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("node has no "
                        + direction.name().toLowerCase() + " port named: " + portName));
    }

    public record PlacedNode(CanvasNode node, int x, int y) {
        public PlacedNode {
            Objects.requireNonNull(node, "node");
            if (x < 0 || y < 0 || x > 100_000 || y > 100_000) {
                throw new IllegalArgumentException("node position is outside the supported canvas bounds");
            }
        }

        public String displayName() {
            return node.name();
        }
    }

    public static String constantNodeType() {
        return CanvasBuiltinExecutors.CONSTANT;
    }

    public static String addNodeType() {
        return CanvasBuiltinExecutors.ADD;
    }

    public static String printNodeType() {
        return CanvasBuiltinExecutors.PRINT;
    }
}
