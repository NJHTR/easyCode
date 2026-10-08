package com.easycode.canvas.execution;

import java.util.Objects;
import java.util.UUID;

/** One actionable issue found while preparing a canvas execution. */
public record CanvasExecutionDiagnostic(
        CanvasExecutionDiagnosticCode code,
        String message,
        UUID nodeId,
        String nodeType) {
    public CanvasExecutionDiagnostic {
        Objects.requireNonNull(code, "code");
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("diagnostic message must not be blank");
        }
    }
}
