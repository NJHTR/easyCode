package com.easycode.execution.api;

import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;

import java.util.Objects;

/** Thin application facade over a host or sandbox execution backend. */
public final class ExecutionService implements AutoCloseable {
    private final ExecutionBackend backend;

    public ExecutionService(ExecutionBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public ExecutionResult execute(ExecutionRequest request) {
        return backend.execute(request);
    }

    @Override
    public void close() {
        backend.close();
    }
}
