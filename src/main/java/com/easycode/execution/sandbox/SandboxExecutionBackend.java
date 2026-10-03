package com.easycode.execution.sandbox;

import com.easycode.execution.api.ExecutionBackend;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.model.ExecutionTerminationReason;
import com.easycode.sandbox.api.SandboxService;
import com.easycode.sandbox.exception.SandboxException;
import com.easycode.sandbox.model.SandboxHandle;
import com.easycode.sandbox.model.SandboxInfo;
import com.easycode.sandbox.model.SandboxLimits;
import com.easycode.sandbox.model.SandboxSpec;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Adapts the existing SandboxService to the execution contract. */
public final class SandboxExecutionBackend implements ExecutionBackend {
    private final SandboxService sandboxService;

    public SandboxExecutionBackend(SandboxService sandboxService) {
        this.sandboxService = Objects.requireNonNull(sandboxService, "sandboxService");
    }

    @Override
    public ExecutionResult execute(ExecutionRequest request) {
        Objects.requireNonNull(request, "request");
        Instant startedAt = Instant.now();
        SandboxHandle handle = null;
        try {
            SandboxLimits limits = new SandboxLimits(
                    request.timeout(), request.maxOutputChars(), 0, 16);
            SandboxSpec spec = new SandboxSpec(
                    "execution-" + request.executionId(),
                    request.command(),
                    request.workingDirectory(),
                    request.environmentVariables(),
                    limits,
                    request.sandboxPolicy());
            handle = sandboxService.create(spec);
            SandboxInfo info = awaitTerminal(handle, request.timeout());
            return result(request, startedAt, info);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            destroyQuietly(handle);
            return result(request, startedAt, ExecutionStatus.CANCELLED, null, "", "",
                    ExecutionTerminationReason.CANCELLED, "execution thread was interrupted");
        } catch (SandboxException | RuntimeException exception) {
            destroyQuietly(handle);
            return result(request, startedAt, ExecutionStatus.FAILED, null, "", "",
                    ExecutionTerminationReason.START_FAILED, exception.getMessage());
        }
    }

    @Override
    public void close() {
        sandboxService.close();
    }

    private SandboxInfo awaitTerminal(SandboxHandle handle, Duration timeout)
            throws SandboxException, InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            SandboxInfo info = sandboxService.query(handle);
            if (info.status() == com.easycode.sandbox.model.SandboxStatus.SUCCEEDED
                    || info.status() == com.easycode.sandbox.model.SandboxStatus.FAILED
                    || info.status() == com.easycode.sandbox.model.SandboxStatus.TIMED_OUT
                    || info.status() == com.easycode.sandbox.model.SandboxStatus.DESTROYED) {
                return info;
            }
            Thread.sleep(10);
        }
        destroyQuietly(handle);
        return sandboxService.query(handle);
    }

    private ExecutionResult result(ExecutionRequest request, Instant startedAt, SandboxInfo info) {
        ExecutionStatus status;
        ExecutionTerminationReason reason;
        String failureMessage = info.error();
        switch (info.status()) {
            case SUCCEEDED -> {
                status = ExecutionStatus.SUCCEEDED;
                reason = ExecutionTerminationReason.COMPLETED;
            }
            case TIMED_OUT -> {
                status = ExecutionStatus.TIMED_OUT;
                reason = ExecutionTerminationReason.TIMED_OUT;
            }
            case DESTROYED -> {
                status = ExecutionStatus.CANCELLED;
                reason = ExecutionTerminationReason.CANCELLED;
            }
            default -> {
                status = ExecutionStatus.FAILED;
                reason = info.exitCode() == null
                        ? ExecutionTerminationReason.INTERNAL_ERROR
                        : ExecutionTerminationReason.NON_ZERO_EXIT;
            }
        }
        return result(request, startedAt, status, info.exitCode(), info.output(), info.error(),
                reason, failureMessage);
    }

    private static ExecutionResult result(
            ExecutionRequest request,
            Instant startedAt,
            ExecutionStatus status,
            Integer exitCode,
            String stdout,
            String stderr,
            ExecutionTerminationReason reason,
            String failureMessage) {
        return new ExecutionResult(request.executionId(), status, exitCode, stdout, stderr,
                Duration.between(startedAt, Instant.now()), reason, failureMessage);
    }

    private void destroyQuietly(SandboxHandle handle) {
        if (handle == null) {
            return;
        }
        try {
            sandboxService.destroy(handle);
        } catch (SandboxException ignored) {
            // Preserve the execution result while making a best-effort cleanup.
        }
    }
}
