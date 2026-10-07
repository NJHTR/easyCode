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
        ExecutionResult result;
        try {
            result = backend.execute(request);
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            result = invalidResult(request,
                    message == null || message.isBlank()
                            ? "execution backend failed"
                            : message);
        }
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
        if (!isConsistent(result)) {
            return invalidResult(request,
                    "execution backend returned an inconsistent status and termination reason");
        }
        return result;
    }

    private static boolean isConsistent(ExecutionResult result) {
        return switch (result.status()) {
            case SUCCEEDED -> result.terminationReason() == ExecutionTerminationReason.COMPLETED
                    && Integer.valueOf(0).equals(result.exitCode());
            case TIMED_OUT -> result.terminationReason() == ExecutionTerminationReason.TIMED_OUT
                    && result.exitCode() == null;
            case CANCELLED -> result.terminationReason() == ExecutionTerminationReason.CANCELLED
                    && result.exitCode() == null;
            case FAILED -> switch (result.terminationReason()) {
                case NON_ZERO_EXIT -> result.exitCode() != null && result.exitCode() != 0;
                case OUTPUT_LIMIT -> true;
                case START_FAILED, INTERNAL_ERROR -> result.exitCode() == null;
                default -> false;
            };
            case CREATED, RUNNING -> false;
        };
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
