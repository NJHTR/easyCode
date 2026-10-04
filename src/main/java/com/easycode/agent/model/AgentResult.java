package com.easycode.agent.model;

import com.easycode.execution.model.ExecutionResult;

import java.util.Objects;

/** Agent-facing result that keeps execution details generic. */
public record AgentResult(
        AgentRun run,
        AgentFailureReason failureReason,
        ExecutionResult executionResult,
        String message) {

    public AgentResult {
        Objects.requireNonNull(run, "run");
        if (!run.terminal()) {
            throw new IllegalArgumentException("result run must be terminal");
        }
        if (run.status() == AgentRunStatus.SUCCEEDED && failureReason != null) {
            throw new IllegalArgumentException("successful result cannot have a failure reason");
        }
        if (run.status() != AgentRunStatus.SUCCEEDED && failureReason == null) {
            throw new IllegalArgumentException("failed result must have a failure reason");
        }
        message = message == null ? "" : message;
    }

    public boolean succeeded() {
        return run.status() == AgentRunStatus.SUCCEEDED;
    }
}
