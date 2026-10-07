package com.easycode.language.model;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Collections;
import java.util.LinkedHashMap;

/** Observable result of one easyCode program run. */
public record EasyCodeExecutionResult(
        UUID programId,
        EasyCodeExecutionStatus status,
        String consoleOutput,
        Map<String, Object> variables,
        int completedInstructionCount,
        String failedInstructionId,
        String failureMessage,
        Duration duration) {

    public EasyCodeExecutionResult {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(status, "status");
        consoleOutput = consoleOutput == null ? "" : consoleOutput;
        variables = variables == null
                ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(variables));
        if (completedInstructionCount < 0) {
            throw new IllegalArgumentException("completedInstructionCount cannot be negative");
        }
        if (status == EasyCodeExecutionStatus.SUCCEEDED && failedInstructionId != null) {
            throw new IllegalArgumentException("successful execution cannot have a failed instruction");
        }
        if (status == EasyCodeExecutionStatus.FAILED
                && (failedInstructionId == null || failedInstructionId.isBlank())) {
            throw new IllegalArgumentException("failed execution must identify the failed instruction");
        }
        failureMessage = failureMessage == null ? "" : failureMessage;
        Objects.requireNonNull(duration, "duration");
        if (duration.isNegative()) {
            throw new IllegalArgumentException("duration cannot be negative");
        }
    }

    public boolean succeeded() {
        return status == EasyCodeExecutionStatus.SUCCEEDED;
    }
}
