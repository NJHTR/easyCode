package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPortDirection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;

/** In-memory port values for one node invocation. */
public final class CanvasNodeExecutionContext {
    private final Map<UUID, Object> inputs;
    private final Set<UUID> inputPortIds;
    private final Set<UUID> outputPortIds;
    private final Map<String, UUID> outputPortsByName;
    private final Map<String, UUID> inputPortsByName;
    private final Map<UUID, Object> outputs = new LinkedHashMap<>();
    private final List<String> consoleOutput = new ArrayList<>();

    CanvasNodeExecutionContext(CanvasNode node, Map<UUID, Object> inputs) {
        Objects.requireNonNull(node, "node");
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.inputPortIds = node.ports().stream()
                .filter(port -> port.direction() == CanvasPortDirection.INPUT)
                .map(port -> port.portId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.outputPortIds = node.ports().stream()
                .filter(port -> port.direction() == CanvasPortDirection.OUTPUT)
                .map(port -> port.portId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        this.outputPortsByName = node.ports().stream()
                .filter(port -> port.direction() == CanvasPortDirection.OUTPUT)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        port -> port.name(), port -> port.portId()));
        this.inputPortsByName = node.ports().stream()
                .filter(port -> port.direction() == CanvasPortDirection.INPUT)
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        port -> port.name(), port -> port.portId()));
    }

    public Object input(UUID portId) {
        UUID requiredPortId = Objects.requireNonNull(portId, "portId");
        if (!inputPortIds.contains(requiredPortId)) {
            throw new IllegalArgumentException("node cannot read from undeclared input port: " + requiredPortId);
        }
        return inputs.get(portId);
    }

    public Object input(String portName) {
        UUID portId = inputPortsByName.get(Objects.requireNonNull(portName, "portName"));
        if (portId == null) {
            throw new IllegalArgumentException("node has no input port named: " + portName);
        }
        return input(portId);
    }

    public boolean hasInput(UUID portId) {
        UUID requiredPortId = Objects.requireNonNull(portId, "portId");
        if (!inputPortIds.contains(requiredPortId)) {
            throw new IllegalArgumentException("node cannot read from undeclared input port: " + requiredPortId);
        }
        return inputs.containsKey(requiredPortId);
    }

    public boolean hasInput(String portName) {
        UUID portId = inputPortsByName.get(Objects.requireNonNull(portName, "portName"));
        if (portId == null) {
            throw new IllegalArgumentException("node has no input port named: " + portName);
        }
        return hasInput(portId);
    }

    public Map<UUID, Object> inputs() {
        return inputs;
    }

    public void output(UUID portId, Object value) {
        UUID requiredPortId = Objects.requireNonNull(portId, "portId");
        if (!outputPortIds.contains(requiredPortId)) {
            throw new IllegalArgumentException("node cannot write to undeclared output port: " + requiredPortId);
        }
        outputs.put(requiredPortId, value);
    }

    public void output(String portName, Object value) {
        UUID portId = outputPortsByName.get(Objects.requireNonNull(portName, "portName"));
        if (portId == null) {
            throw new IllegalArgumentException("node has no output port named: " + portName);
        }
        output(portId, value);
    }

    public void console(Object value) {
        consoleOutput.add(String.valueOf(value));
    }

    Map<UUID, Object> outputsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    List<String> consoleOutputSnapshot() {
        return List.copyOf(consoleOutput);
    }
}
