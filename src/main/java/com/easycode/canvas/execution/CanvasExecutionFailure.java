package com.easycode.canvas.execution;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Objects;

/** Immutable diagnostic details for an exception thrown during node execution. */
public record CanvasExecutionFailure(String exceptionType, String message, String stackTrace) {
    public CanvasExecutionFailure {
        if (exceptionType == null || exceptionType.isBlank()) {
            throw new IllegalArgumentException("exception type must not be blank");
        }
        message = message == null ? "" : message;
        stackTrace = stackTrace == null ? "" : stackTrace;
    }

    public static CanvasExecutionFailure from(Throwable throwable) {
        Objects.requireNonNull(throwable, "throwable");
        StringWriter text = new StringWriter();
        throwable.printStackTrace(new PrintWriter(text));
        String message = throwable.getMessage();
        return new CanvasExecutionFailure(throwable.getClass().getName(),
                message == null || message.isBlank() ? throwable.getClass().getSimpleName() : message,
                text.toString());
    }
}
