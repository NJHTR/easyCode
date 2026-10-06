package com.easycode.execution.api;

import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.model.ExecutionTerminationReason;

import java.time.Duration;
import java.util.Objects;

/** Thin application facade over a host or sandbox execution backend. */
public final class ExecutionService implements AutoCloseable {
    private final ExecutionBackend backend;

    public ExecutionService(ExecutionBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionResult result = backend.execute(request);
        if (result == null) {
            return invalidResult(request, "execution backend returned no result");
        }
        if (!request.executionId().equals(result.executionId())) {
            return invalidResult(request, "execution backend returned a mismatched execution id");
        }
        if (result.status() == ExecutionStatus.CREATED
                || result.status() == ExecutionStatus.RUNNING) {
            return invalidResult(request,
                    "execution backend returned a non-terminal status: " + result.status());
        }
        return result;
    }

    private static ExecutionResult invalidResult(ExecutionRequest request, String message) {
        return new ExecutionResult(
                request.executionId(),
                ExecutionStatus.FAILED,
                null,
                "",
                "",
                Duration.ZERO,
                ExecutionTerminationReason.INTERNAL_ERROR,
                message);
    }

    @Override
    public void close() {
        backend.close();
    }
}
