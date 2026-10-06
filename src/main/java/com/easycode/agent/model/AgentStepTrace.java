package com.easycode.agent.model;

import com.easycode.tool.model.ToolFailureReason;

import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable, provider-neutral observation of one LLM generation. */
public record AgentStepTrace(
        int stepNumber,
        Instant startedAt,
        Instant completedAt,
        String model,
        int messageCount,
        int availableToolCount,
        boolean responseContentPresent,
        int toolCallCount,
        List<ToolObservation> toolObservations,
        AgentStepOutcome outcome) {

    public AgentStepTrace {
        if (stepNumber <= 0) {
            throw new IllegalArgumentException("stepNumber must be positive");
        }
        Objects.requireNonNull(startedAt, "startedAt");
        Objects.requireNonNull(completedAt, "completedAt");
        if (completedAt.isBefore(startedAt)) {
            throw new IllegalArgumentException("completedAt cannot be before startedAt");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (messageCount < 0 || availableToolCount < 0 || toolCallCount < 0) {
            throw new IllegalArgumentException("observation counts cannot be negative");
        }
        toolObservations = toolObservations == null
                ? List.of() : List.copyOf(toolObservations);
        if (toolObservations.size() > toolCallCount) {
            throw new IllegalArgumentException("too many Tool observations");
        }
        Set<UUID> observationIds = new HashSet<>();
        for (ToolObservation observation : toolObservations) {
            Objects.requireNonNull(observation, "toolObservations cannot contain null");
            if (!observationIds.add(observation.callId())) {
                throw new IllegalArgumentException(
                        "step cannot contain duplicate Tool call id: " + observation.callId());
            }
        }
        Objects.requireNonNull(outcome, "outcome");
    }

    public Duration duration() {
        return Duration.between(startedAt, completedAt);
    }

    public record ToolObservation(
            UUID callId,
            String toolName,
            boolean succeeded,
            ToolFailureReason failureReason,
            boolean inputPresent,
            boolean outputPresent,
            int inputLength,
            int outputLength) {
        public ToolObservation {
            Objects.requireNonNull(callId, "callId");
            if (toolName == null || toolName.isBlank()) {
                throw new IllegalArgumentException("toolName must not be blank");
            }
            if (succeeded && failureReason != null) {
                throw new IllegalArgumentException(
                        "successful Tool observation cannot have a failure reason");
            }
            if (!succeeded && failureReason == null) {
                throw new IllegalArgumentException(
                        "failed Tool observation must have a failure reason");
            }
            if (inputLength < 0 || outputLength < 0) {
                throw new IllegalArgumentException("content lengths cannot be negative");
            }
        }

        public static ToolObservation from(
                com.easycode.tool.model.ToolInvocation invocation,
                com.easycode.tool.model.ToolResult result) {
            String input = invocation.input();
            String output = result.succeeded() ? result.output() : result.error();
            return new ToolObservation(
                    invocation.callId(), invocation.toolName(), result.succeeded(),
                    result.failureReason(), !input.isEmpty(), !output.isEmpty(),
                    input.length(), output.length());
        }
    }
}
