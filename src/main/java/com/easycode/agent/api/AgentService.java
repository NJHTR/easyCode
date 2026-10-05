package com.easycode.agent.api;

import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRun;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.model.ExecutionStatus;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentExecution;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Deterministic Agent facade over the runtime-independent execution port. */
public final class AgentService {
    private final AgentExecutionPort executionPort;
    private final AgentOrchestrator orchestrator;

    public AgentService(AgentExecutionPort executionPort) {
        this.executionPort = Objects.requireNonNull(executionPort, "executionPort");
        this.orchestrator = null;
    }

    public AgentService(AgentOrchestrator orchestrator) {
        this.executionPort = null;
        this.orchestrator = Objects.requireNonNull(orchestrator, "orchestrator");
    }

    public AgentResult run(AgentRequest request) {
        if (executionPort == null) {
            throw new IllegalStateException("AgentService is configured for LLM orchestration");
        }
        Objects.requireNonNull(request, "request");
        UUID runId = UUID.randomUUID();
        Instant createdAt = Instant.now();
        Instant startedAt = Instant.now();

        try {
            ExecutionResult executionResult = executionPort.execute(request);
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

    public AgentResult run(AgentPromptRequest request) {
        if (orchestrator == null) {
            throw new IllegalStateException("AgentService is configured for execution");
        }
        return orchestrator.run(request);
    }

    public AgentExecution runWithTrace(AgentPromptRequest request) {
        if (orchestrator == null) {
            throw new IllegalStateException("AgentService is configured for execution");
        }
        return orchestrator.runWithTrace(request);
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
