package com.easycode.execution.api;

import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;

/** Executes one request in a selected execution environment. */
public interface ExecutionBackend extends AutoCloseable {
    ExecutionResult execute(ExecutionRequest request);

    @Override
    void close();
}
