package com.easycode.execution.routing;

import com.easycode.execution.api.ExecutionBackend;
import com.easycode.execution.model.ExecutionEnvironment;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.model.ExecutionTerminationReason;

import java.time.Duration;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Routes each request to the backend for its declared execution environment. */
public final class EnvironmentExecutionBackend implements ExecutionBackend {
    private final Map<ExecutionEnvironment, ExecutionBackend> backends;

    public EnvironmentExecutionBackend(
            ExecutionBackend hostBackend, ExecutionBackend sandboxBackend) {
        this(Map.of(
                ExecutionEnvironment.HOST, hostBackend,
                ExecutionEnvironment.SANDBOX, sandboxBackend));
    }

    public EnvironmentExecutionBackend(
            Map<ExecutionEnvironment, ? extends ExecutionBackend> backends) {
        Objects.requireNonNull(backends, "backends");
        if (backends.isEmpty()) {
            throw new IllegalArgumentException("at least one execution backend is required");
        }
        EnumMap<ExecutionEnvironment, ExecutionBackend> copy =
                new EnumMap<>(ExecutionEnvironment.class);
        for (Map.Entry<ExecutionEnvironment, ? extends ExecutionBackend> entry
                : backends.entrySet()) {
            copy.put(Objects.requireNonNull(entry.getKey(), "backend environment"),
                    Objects.requireNonNull(entry.getValue(), "backend"));
        }
        this.backends = Map.copyOf(copy);
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionBackend backend = backends.get(request.executionEnvironment());
        if (backend == null) {
            return new ExecutionResult(
                    request.executionId(),
                    ExecutionStatus.FAILED,
                    null,
                    "",
                    "",
                    Duration.ZERO,
                    ExecutionTerminationReason.START_FAILED,
                    "no execution backend configured for environment: "
                            + request.executionEnvironment());
        }
        return backend.execute(request);
    }

    @Override
    public void close() {
        Set<ExecutionBackend> uniqueBackends = new LinkedHashSet<>(backends.values());
        uniqueBackends.forEach(ExecutionBackend::close);
    }
}
