package com.easycode.canvas.exception;

/** Reports an invalid canvas graph structure. */
public final class CanvasValidationException extends IllegalArgumentException {
    public CanvasValidationException(String message) {
        super(message);
    }
}
