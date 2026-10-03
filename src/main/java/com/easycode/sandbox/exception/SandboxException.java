package com.easycode.sandbox.exception;

import java.io.Serial;

public class SandboxException extends Exception {
    @Serial
    private static final long serialVersionUID = 1L;

    public SandboxException(String message) {
        super(message);
    }

    public SandboxException(String message, Throwable cause) {
        super(message, cause);
    }
}
