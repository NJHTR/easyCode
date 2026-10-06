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
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Synchronous, bounded coordinator for model responses and Tool calls. */
public final class AgentOrchestrator {
    private static final int MAX_TOOL_MESSAGE_CHARS = 32_768;
    private static final int MAX_TOOL_CALLS_PER_STEP = 32;
    private static final String TOOL_OUTPUT_TRUNCATED_MARKER = "\n[tool output truncated]";

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
                response = generateWithTimeout(request.timeout(), new com.easycode.llm.model.LlmRequest(
                        request.model(), messages, toolAccess.listTools()));
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, false, 0, List.of(),
                        AgentStepOutcome.CANCELLED));
                return execution(request, runId, createdAt, AgentRunStatus.CANCELLED,
                        AgentFailureReason.CANCELLED, "Agent orchestration interrupted", steps);
            } catch (TimeoutException exception) {
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, false, 0, List.of(),
                        AgentStepOutcome.TIMEOUT));
                return execution(request, runId, createdAt, AgentRunStatus.TIMED_OUT,
                        AgentFailureReason.TIMEOUT, "LLM provider call exceeded "
                                + request.timeout().toMillis() + " ms", steps);
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
            if (response.toolCalls().size() > MAX_TOOL_CALLS_PER_STEP) {
                steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                        messageCount, availableToolCount, !response.content().isBlank(),
                        response.toolCalls().size(), List.of(),
                        AgentStepOutcome.MAX_TOOL_CALLS_REACHED));
                return execution(request, runId, createdAt, AgentRunStatus.FAILED,
                        AgentFailureReason.MAX_TOOL_CALLS_REACHED,
                        "maximum Tool calls per step reached: " + MAX_TOOL_CALLS_PER_STEP, steps);
            }

            messages.add(LlmMessage.assistant(response.content(), response.toolCalls()));
            List<AgentStepTrace.ToolObservation> observations = new ArrayList<>();
            for (LlmToolCall call : response.toolCalls()) {
                ToolInvocation invocation = new ToolInvocation(
                        call.callId(), call.toolName(), call.arguments());
                ToolResult result;
                try {
                    result = invokeToolWithTimeout(request.timeout(), invocation);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    result = ToolResult.failure(
                            invocation.callId(),
                            com.easycode.tool.model.ToolFailureReason.INTERNAL_ERROR,
                            "Agent orchestration interrupted");
                    observations.add(AgentStepTrace.ToolObservation.from(invocation, result));
                    messages.add(LlmMessage.tool(call.callId(), call.toolName(), toolMessage(result)));
                    steps.add(new AgentStepTrace(step, stepStartedAt, Instant.now(), request.model(),
                            messageCount, availableToolCount, !response.content().isBlank(),
                            response.toolCalls().size(), observations, AgentStepOutcome.CANCELLED));
                    return execution(request, runId, createdAt, AgentRunStatus.CANCELLED,
                            AgentFailureReason.CANCELLED, "Agent orchestration interrupted", steps);
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

    private ToolResult invokeToolWithTimeout(Duration timeout, ToolInvocation invocation)
            throws InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "easycode-agent-tool");
            thread.setDaemon(true);
            return thread;
        });
        Future<ToolResult> future = executor.submit(() -> toolAccess.invoke(invocation));
        try {
            ToolResult result = future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
            return result == null
                    ? ToolResult.failure(invocation.callId(),
                            com.easycode.tool.model.ToolFailureReason.INTERNAL_ERROR,
                            "Tool access returned no result")
                    : result;
        } catch (TimeoutException exception) {
            future.cancel(true);
            return ToolResult.failure(
                    invocation.callId(),
                    com.easycode.tool.model.ToolFailureReason.TIMEOUT,
                    "Tool invocation exceeded " + timeout.toMillis() + " ms");
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private LlmResponse generateWithTimeout(Duration timeout,
            com.easycode.llm.model.LlmRequest request)
            throws LlmException, TimeoutException, InterruptedException {
        ExecutorService executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "easycode-agent-llm");
            thread.setDaemon(true);
            return thread;
        });
        Future<LlmResponse> future = executor.submit(() -> llmProvider.generate(request));
        try {
            return future.get(timeout.toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException exception) {
            future.cancel(true);
            throw exception;
        } catch (InterruptedException exception) {
            future.cancel(true);
            throw exception;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof LlmException llmException) {
                throw llmException;
            }
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new RuntimeException(cause);
        } finally {
            executor.shutdownNow();
        }
    }

    private static String toolMessage(ToolResult result) {
        String message = result.succeeded()
                ? result.output()
                : "Tool failure [" + result.failureReason() + "]: " + result.error();
        if (message.length() <= MAX_TOOL_MESSAGE_CHARS) {
            return message;
        }
        int contentLength = MAX_TOOL_MESSAGE_CHARS - TOOL_OUTPUT_TRUNCATED_MARKER.length();
        return message.substring(0, contentLength) + TOOL_OUTPUT_TRUNCATED_MARKER;
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
