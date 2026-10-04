package com.easycode.tool.model;

import java.util.Objects;
import java.util.UUID;

/** Final result of a Tool invocation without leaking implementation exceptions. */
public record ToolResult(
        UUID callId,
        ToolResultStatus status,
        String output,
        ToolFailureReason failureReason,
        String error) {

    public ToolResult {
        Objects.requireNonNull(callId, "callId");
        Objects.requireNonNull(status, "status");
        output = output == null ? "" : output;
        error = error == null ? "" : error;
        if (status == ToolResultStatus.SUCCESS) {
            if (failureReason != null || !error.isBlank()) {
                throw new IllegalArgumentException(
                        "successful result cannot contain failure details");
            }
        } else if (failureReason == null || error.isBlank()) {
            throw new IllegalArgumentException(
                    "failed result must contain a reason and error");
        }
    }

    public static ToolResult success(UUID callId, String output) {
        return new ToolResult(callId, ToolResultStatus.SUCCESS, output, null, "");
    }

    public static ToolResult failure(
            UUID callId, ToolFailureReason reason, String error) {
        return new ToolResult(callId, ToolResultStatus.FAILURE, "", reason, error);
    }

    public boolean succeeded() {
        return status == ToolResultStatus.SUCCESS;
    }
}
