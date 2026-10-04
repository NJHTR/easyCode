package com.easycode.agent.api;

import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRun;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.execution.api.ExecutionService;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.execution.runtime.jvm.JvmWorkerRuntime;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Deterministic Agent facade over the existing JVM runtime and Execution layer. */
public final class AgentService {
    private final ExecutionService executionService;
    private final JvmWorkerRuntime runtime;

    public AgentService(ExecutionService executionService) {
        this(executionService, new JvmWorkerRuntime());
    }

    public AgentService(ExecutionService executionService, JvmWorkerRuntime runtime) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public AgentResult run(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant startedAt = Instant.now();

        try {
            ExecutionRequest executionRequest = runtime.request(
                    request.action(),
                    request.input(),
                    request.executionEnvironment(),
                    request.timeout());
            ExecutionResult executionResult = executionService.execute(executionRequest);
            Instant finishedAt = Instant.now();
            AgentRun run = new AgentRun(
                    runId,
                    request.requestId(),
                    statusOf(executionResult),
                    createdAt,
                    startedAt,
                    finishedAt);
            return new AgentResult(
                    run,
                    failureReasonOf(executionResult),
                    executionResult,
                    messageOf(executionResult));
        } catch (RuntimeException exception) {
            Instant finishedAt = Instant.now();
            AgentRun run = new AgentRun(
                    runId,
                    request.requestId(),
                    AgentRunStatus.FAILED,
                    createdAt,
                    startedAt,
                    finishedAt);
            return new AgentResult(
                    run,
                    AgentFailureReason.RUNTIME_FAILURE,
                    null,
                    exception.getMessage() == null
                            ? "agent runtime failed"
                            : exception.getMessage());
        }
    }

    private static AgentRunStatus statusOf(ExecutionResult result) {
        return switch (result.status()) {
            case SUCCEEDED -> AgentRunStatus.SUCCEEDED;
            case TIMED_OUT -> AgentRunStatus.TIMED_OUT;
            case CANCELLED -> AgentRunStatus.CANCELLED;
            default -> AgentRunStatus.FAILED;
        };
    }

    private static AgentFailureReason failureReasonOf(ExecutionResult result) {
        if (result.status() == ExecutionStatus.SUCCEEDED) {
            return null;
        }
        return switch (result.terminationReason()) {
            case TIMED_OUT -> AgentFailureReason.TIMEOUT;
            case CANCELLED -> AgentFailureReason.CANCELLED;
            case START_FAILED -> AgentFailureReason.ENVIRONMENT_FAILURE;
            case INTERNAL_ERROR -> AgentFailureReason.RUNTIME_FAILURE;
            default -> AgentFailureReason.EXECUTION_FAILURE;
        };
    }

    private static String messageOf(ExecutionResult result) {
        if (result.status() == ExecutionStatus.SUCCEEDED) {
            return "";
        }
        if (!result.failureMessage().isBlank()) {
            return result.failureMessage();
        }
        if (!result.stderr().isBlank()) {
            return result.stderr();
        }
        return "execution terminated with " + result.terminationReason();
    }
}
