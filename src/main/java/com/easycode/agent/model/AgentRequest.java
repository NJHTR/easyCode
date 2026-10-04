package com.easycode.agent.model;

import com.easycode.execution.model.ExecutionEnvironment;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/** A small deterministic request accepted by the current Agent core. */
public record AgentRequest(
        UUID requestId,
        String action,
        String input,
        ExecutionEnvironment executionEnvironment,
        Duration timeout) {

    public AgentRequest {
        Objects.requireNonNull(requestId, "requestId");
        if (action == null || action.isBlank() || action.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("action must be a non-empty single line");
        }
        if (input == null || input.indexOf('\n') >= 0) {
            throw new IllegalArgumentException("input must be a non-null single line");
        }
        Objects.requireNonNull(executionEnvironment, "executionEnvironment");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public static AgentRequest create(
            String action,
            String input,
            ExecutionEnvironment executionEnvironment,
            Duration timeout) {
        return new AgentRequest(
                UUID.randomUUID(), action, input, executionEnvironment, timeout);
    }
}
