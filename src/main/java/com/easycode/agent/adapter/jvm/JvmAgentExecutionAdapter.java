package com.easycode.agent.adapter.jvm;

import com.easycode.agent.api.AgentExecutionPort;
import com.easycode.agent.model.AgentRequest;
import com.easycode.execution.api.ExecutionService;
import com.easycode.execution.model.ExecutionRequest;
import com.easycode.execution.model.ExecutionResult;
import com.easycode.execution.runtime.jvm.JvmWorkerRuntime;

import java.util.Objects;

/** Current JVM implementation of the Agent execution port. */
public final class JvmAgentExecutionAdapter implements AgentExecutionPort {
    private final ExecutionService executionService;
    private final JvmWorkerRuntime runtime;

    public JvmAgentExecutionAdapter(ExecutionService executionService) {
        this(executionService, new JvmWorkerRuntime());
    }

    public JvmAgentExecutionAdapter(ExecutionService executionService, JvmWorkerRuntime runtime) {
        this.executionService = Objects.requireNonNull(executionService, "executionService");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    @Override
    public ExecutionResult execute(AgentRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionRequest executionRequest = runtime.request(
                request.action(),
                request.input(),
                request.executionEnvironment(),
                request.timeout());
        return executionService.execute(executionRequest);
    }
}
