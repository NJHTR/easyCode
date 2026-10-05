package com.easycode.agent.api;

import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRun;
import com.easycode.agent.model.AgentRunStatus;
import com.easycode.agent.model.AgentRunTrace;
import com.easycode.agent.model.AgentStepOutcome;
import com.easycode.agent.model.AgentStepTrace;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmResponse;
import com.easycode.llm.model.LlmToolCall;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Synchronous, bounded coordinator for model responses and Tool calls. */
public final class AgentOrchestrator {
    private final LlmProvider llmProvider;
    private final AgentToolAccess toolAccess;
    private final int maxSteps;

    public AgentOrchestrator(LlmProvider llmProvider, AgentToolAccess toolAccess, int maxSteps) {
        this.llmProvider = Objects.requireNonNull(llmProvider, "llmProvider");
        this.toolAccess = Objects.requireNonNull(toolAccess, "toolAccess");
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }

    public AgentResult run(AgentPromptRequest request) {
        return runWithTrace(request).result();
    }

    public AgentExecution runWithTrace(AgentPromptRequest request) {
        Objects.requireNonNull(request, "request");
        Instant createdAt = Instant.now();
        UUID runId = UUID.randomUUID();
        List<LlmMessage> messages = new ArrayList<>(request.messages());
        List<AgentStepTrace> steps = new ArrayList<>();

        for (int step = 1; step <= maxSteps; step++) {
            Instant stepStartedAt = Instant.now();
            int messageCount = messages.size();
            int availableToolCount = toolAccess.listTools().size();
            LlmResponse response;
            try {
                response = llmProvider.generate(new com.easycode.llm.model.LlmRequest(
                        request.model(), messages, toolAccess.listTools()));
            } catch (LlmException | RuntimeException exception) {
                String message = messageOf(exception, "LLM provider failed");
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, false, 0, List.of(),
                        AgentStepOutcome.LLM_FAILURE));
                return execution(request, runId, createdAt, AgentRunStatus.FAILED,
                        AgentFailureReason.LLM_FAILURE, message, steps);
            }

            if (response == null) {
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, false, 0, List.of(),
                        AgentStepOutcome.INVALID_RESPONSE));
                return execution(request, runId, createdAt, AgentRunStatus.FAILED,
                        AgentFailureReason.INVALID_RESPONSE, "LLM provider returned no response", steps);
            }
            if (response.toolCalls().isEmpty()) {
                if (response.content().isBlank()) {
                    steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                            messageCount, availableToolCount, false, 0, List.of(),
                            AgentStepOutcome.INVALID_RESPONSE));
                    return execution(request, runId, createdAt, AgentRunStatus.FAILED,
                            AgentFailureReason.INVALID_RESPONSE,
                            "LLM response contained neither text nor Tool calls", steps);
                }
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, true, 0, List.of(),
                        AgentStepOutcome.COMPLETED));
                return execution(request, runId, createdAt, AgentRunStatus.SUCCEEDED,
                        null, response.content(), steps);
            }
            if (step == maxSteps) {
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, !response.content().isBlank(),
                        response.toolCalls().size(), List.of(), AgentStepOutcome.MAX_STEPS_REACHED));
                return execution(request, runId, createdAt, AgentRunStatus.FAILED,
                        AgentFailureReason.MAX_STEPS_REACHED, "maximum Agent steps reached", steps);
            }

            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            List<AgentStepTrace.ToolObservation> observations = new ArrayList<>();
            for (LlmToolCall call : response.toolCalls()) {
                ToolInvocation invocation = new ToolInvocation(
                        call.callId(), call.toolName(), call.arguments());
                ToolResult result;
                try {
                    result = toolAccess.invoke(invocation);
                } catch (RuntimeException exception) {
                    result = ToolResult.failure(
                            invocation.callId(),
                            com.easycode.tool.model.ToolFailureReason.INTERNAL_ERROR,
                            messageOf(exception, "Tool access failed"));
                }
                observations.add(AgentStepTrace.ToolObservation.from(invocation, result));
                messages.add(LlmMessage.tool(call.callId(), call.toolName(), toolMessage(result)));
            }
            steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                    messageCount, availableToolCount, !response.content().isBlank(),
                    response.toolCalls().size(), observations, AgentStepOutcome.TOOL_CALLS));
        }
        return execution(request, runId, createdAt, AgentRunStatus.FAILED,
                AgentFailureReason.MAX_STEPS_REACHED, "maximum Agent steps reached", steps);
    }

    private static String toolMessage(ToolResult result) {
        return result.succeeded()
                ? result.output()
                : "Tool failure [" + result.failureReason() + "]: " + result.error();
    }

    private static AgentExecution execution(AgentPromptRequest request, UUID runId,
            Instant createdAt, AgentRunStatus status, AgentFailureReason reason,
            String message, List<AgentStepTrace> steps) {
        Instant finishedAt = Instant.now();
        AgentRun run = new AgentRun(runId, request.requestId(), status, createdAt,
                createdAt, finishedAt);
        AgentResult result = new AgentResult(run, reason, null, message);
        return new AgentExecution(result,
                new AgentRunTrace(runId, request.requestId(), steps, status, reason));
    }

    private static String messageOf(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback : exception.getMessage();
    }
}
