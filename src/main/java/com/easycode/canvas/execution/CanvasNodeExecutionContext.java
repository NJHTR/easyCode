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
import java.util.function.Consumer;

/** In-memory port values for one node invocation. */
public final class CanvasNodeExecutionContext {
    private final Map<UUID, Object> inputs;
    private final Set<UUID> inputPortIds;
    private final Set<UUID> outputPortIds;
    private final Map<String, UUID> outputPortsByName;
    private final Map<String, UUID> inputPortsByName;
    private final CanvasExecutionCancellationToken cancellationToken;
    private final Consumer<String> consoleSink;
    private final Consumer<Map<UUID, Object>> outputSink;
    private final Map<UUID, Object> outputs = new LinkedHashMap<>();
    private final List<String> consoleOutput = new ArrayList<>();

    CanvasNodeExecutionContext(CanvasNode node, Map<UUID, Object> inputs) {
        this(node, inputs, new CanvasExecutionCancellationToken());
    }

    CanvasNodeExecutionContext(CanvasNode node, Map<UUID, Object> inputs,
                               CanvasExecutionCancellationToken cancellationToken) {
        this(node, inputs, cancellationToken, ignored -> { });
    }

    CanvasNodeExecutionContext(CanvasNode node, Map<UUID, Object> inputs,
                               CanvasExecutionCancellationToken cancellationToken,
                               Consumer<String> consoleSink) {
        this(node, inputs, cancellationToken, consoleSink, ignored -> { });
    }

    CanvasNodeExecutionContext(CanvasNode node, Map<UUID, Object> inputs,
                               CanvasExecutionCancellationToken cancellationToken,
                               Consumer<String> consoleSink,
                               Consumer<Map<UUID, Object>> outputSink) {
        Objects.requireNonNull(node, "node");
        this.cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
        this.consoleSink = Objects.requireNonNull(consoleSink, "consoleSink");
        this.outputSink = Objects.requireNonNull(outputSink, "outputSink");
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

    public boolean isCancellationRequested() {
        return cancellationToken.isCancellationRequested();
    }

    public void throwIfCancellationRequested() {
        cancellationToken.throwIfCancellationRequested();
    }

    public synchronized void output(UUID portId, Object value) {
        UUID requiredPortId = Objects.requireNonNull(portId, "portId");
        if (!outputPortIds.contains(requiredPortId)) {
            throw new IllegalArgumentException("node cannot write to undeclared output port: " + requiredPortId);
        }
        outputs.put(requiredPortId, value);
        outputSink.accept(outputsSnapshot());
    }

    public synchronized void output(String portName, Object value) {
        UUID portId = outputPortsByName.get(Objects.requireNonNull(portName, "portName"));
        if (portId == null) {
            throw new IllegalArgumentException("node has no output port named: " + portName);
        }
        output(portId, value);
    }

    public synchronized void console(Object value) {
        String line = String.valueOf(value);
        consoleOutput.add(line);
        consoleSink.accept(line);
    }

    synchronized Map<UUID, Object> outputsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }

    synchronized List<String> consoleOutputSnapshot() {
        return List.copyOf(consoleOutput);
    }
}
