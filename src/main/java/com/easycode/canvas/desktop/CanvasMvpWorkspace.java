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
import com.easycode.canvas.model.CanvasPortDirection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Mutable document state for the first local canvas editor. */
public final class CanvasMvpWorkspace {
    private final CanvasApplication application;
    private final UUID canvasId = UUID.randomUUID();
    private final String name;
    private final Map<UUID, PlacedNode> nodes = new LinkedHashMap<>();
    private final List<CanvasConnection> connections = new ArrayList<>();
    private int nextNodeNumber = 1;

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
        return placed;
    }

    public synchronized void moveNode(UUID nodeId, int x, int y) {
        PlacedNode current = requireNode(nodeId);
        nodes.put(nodeId, new PlacedNode(current.node(), Math.max(8, x), Math.max(8, y)));
    }

    public synchronized CanvasConnection connect(UUID fromNodeId, String fromPortName,
                                                 UUID toNodeId, String toPortName) {
        CanvasPort from = requirePort(fromNodeId, fromPortName, CanvasPortDirection.OUTPUT);
        CanvasPort to = requirePort(toNodeId, toPortName, CanvasPortDirection.INPUT);
        CanvasConnection connection = new CanvasConnection(UUID.randomUUID(), fromNodeId,
                from.portId(), toNodeId, to.portId());
        connections.add(connection);
        return connection;
    }

    public synchronized CanvasDefinition definition() {
        return new CanvasDefinition(canvasId, name,
                nodes.values().stream().map(PlacedNode::node).toList(), List.copyOf(connections));
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
