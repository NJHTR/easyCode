package com.easycode.agent.model;

import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Immutable in-memory trace for one Agent run; it is not persistence or logging. */
public record AgentRunTrace(
        UUID runId,
        UUID requestId,
        List<AgentStepTrace> steps,
        AgentRunStatus outcome,
        AgentFailureReason failureReason) {
    public AgentRunTrace {
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(requestId, "requestId");
        steps = steps == null ? List.of() : List.copyOf(steps);
        Set<Integer> stepNumbers = new HashSet<>();
        for (AgentStepTrace step : steps) {
            Objects.requireNonNull(step, "steps cannot contain null");
            if (!stepNumbers.add(step.stepNumber())) {
                throw new IllegalArgumentException(
                        "trace cannot contain duplicate step number: " + step.stepNumber());
            }
        }
        Objects.requireNonNull(outcome, "outcome");
        if (!isTerminal(outcome)) {
            throw new IllegalArgumentException("trace outcome must be terminal");
        }
        if (outcome == AgentRunStatus.SUCCEEDED && failureReason != null) {
            throw new IllegalArgumentException("successful trace cannot have a failure reason");
        }
        if (outcome != AgentRunStatus.SUCCEEDED && failureReason == null) {
            throw new IllegalArgumentException("failed trace must have a failure reason");
        }
    }

    private static boolean isTerminal(AgentRunStatus status) {
        return status == AgentRunStatus.SUCCEEDED
                || status == AgentRunStatus.FAILED
                || status == AgentRunStatus.TIMED_OUT
                || status == AgentRunStatus.CANCELLED;
    }
}
