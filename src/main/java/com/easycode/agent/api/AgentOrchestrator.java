package com.easycode.agent.api;

import com.easycode.agent.model.AgentFailureReason;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.agent.model.AgentRun;
import com.easycode.agent.model.AgentRunStatus;
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
        Objects.requireNonNull(request, "request");
        Instant createdAt = Instant.now();
        List<LlmMessage> messages = new ArrayList<>(request.messages());

        for (int step = 1; step <= maxSteps; step++) {
            LlmResponse response;
            try {
                response = llmProvider.generate(new com.easycode.llm.model.LlmRequest(
                        request.model(), messages, toolAccess.listTools()));
            } catch (LlmException | RuntimeException exception) {
                return failure(request, createdAt, AgentFailureReason.LLM_FAILURE,
                        messageOf(exception, "LLM provider failed"));
            }

            if (response == null) {
                return failure(request, createdAt, AgentFailureReason.INVALID_RESPONSE,
                        "LLM provider returned no response");
            }
            if (response.toolCalls().isEmpty()) {
                if (response.content().isBlank()) {
                    return failure(request, createdAt, AgentFailureReason.INVALID_RESPONSE,
                            "LLM response contained neither text nor Tool calls");
                }
                return success(request, createdAt, response.content());
            }
            if (step == maxSteps) {
                return failure(request, createdAt, AgentFailureReason.MAX_STEPS_REACHED,
                        "maximum Agent steps reached");
            }

            if (!response.content().isBlank()) {
                messages.add(LlmMessage.assistant(response.content()));
            }
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
                messages.add(LlmMessage.tool(call.callId(), call.toolName(), toolMessage(result)));
            }
        }
        return failure(request, createdAt, AgentFailureReason.MAX_STEPS_REACHED,
                "maximum Agent steps reached");
    }

    private static String toolMessage(ToolResult result) {
        return result.succeeded()
                ? result.output()
                : "Tool failure [" + result.failureReason() + "]: " + result.error();
    }

    private static AgentResult success(AgentPromptRequest request, Instant createdAt, String content) {
        return new AgentResult(terminalRun(request, createdAt, AgentRunStatus.SUCCEEDED), null,
                null, content);
    }

    private static AgentResult failure(AgentPromptRequest request, Instant createdAt,
            AgentFailureReason reason, String message) {
        return new AgentResult(terminalRun(request, createdAt, AgentRunStatus.FAILED), reason,
                null, message);
    }

    private static AgentRun terminalRun(AgentPromptRequest request, Instant createdAt,
            AgentRunStatus status) {
        Instant finishedAt = Instant.now();
        return new AgentRun(UUID.randomUUID(), request.requestId(), status, createdAt,
                createdAt, finishedAt);
    }

    private static String messageOf(Exception exception, String fallback) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? fallback : exception.getMessage();
    }
}
