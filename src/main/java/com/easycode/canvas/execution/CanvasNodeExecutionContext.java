package com.easycode.canvas.execution;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** In-memory port values for one node invocation. */
public final class CanvasNodeExecutionContext {
    private final Map<UUID, Object> inputs;
    private final Map<UUID, Object> outputs = new LinkedHashMap<>();

    CanvasNodeExecutionContext(Map<UUID, Object> inputs) {
        this.inputs = Collections.unmodifiableMap(new LinkedHashMap<>(inputs));
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
        outputs.put(Objects.requireNonNull(portId, "portId"), value);
    }

    Map<UUID, Object> outputsSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(outputs));
    }
}
