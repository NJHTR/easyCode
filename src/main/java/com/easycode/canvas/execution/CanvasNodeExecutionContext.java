package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasNode;
import com.easycode.canvas.model.CanvasPortDirection;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** In-memory port values for one node invocation. */
public final class CanvasNodeExecutionContext {
    private final Map<UUID, Object> inputs;
    private final Set<UUID> outputPortIds;
    private final Map<UUID, Object> outputs = new LinkedHashMap<>();

    CanvasNodeExecutionContext(CanvasNode node, Map<UUID, Object> inputs) {
        Objects.requireNonNull(node, "node");
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
        this.outputPortIds = node.ports().stream()
                .filter(port -> port.direction() == CanvasPortDirection.OUTPUT)
                .map(port -> port.portId())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    public Object input(UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return inputs.get(portId);
    }

    public boolean hasInput(UUID portId) {
        Objects.requireNonNull(portId, "portId");
        return inputs.containsKey(portId);
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

    Map<UUID, Object> outputsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }
}
