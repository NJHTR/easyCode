package com.easycode.agent.composition;

import com.easycode.agent.adapter.tool.RegistryAgentToolAccess;
import com.easycode.agent.api.AgentOrchestrator;
import com.easycode.agent.api.AgentService;
import com.easycode.agent.model.AgentExecution;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.llm.api.LlmProvider;
import com.easycode.tool.api.ToolRegistry;

import java.util.Objects;

/**
 * Local composition boundary for the current synchronous Agent core.
 *
 * <p>This class owns wiring, while the Agent loop remains in
 * {@link AgentOrchestrator}. It does not select providers, register Tools, or
 * add a runtime, persistence, or scheduling concern.</p>
 */
public final class LocalAgentComposition {
    private final AgentService agentService;

    public LocalAgentComposition(LlmProvider llmProvider, ToolRegistry toolRegistry, int maxSteps) {
        Objects.requireNonNull(llmProvider, "llmProvider");
        Objects.requireNonNull(toolRegistry, "toolRegistry");
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmProvider,
                new RegistryAgentToolAccess(toolRegistry),
                maxSteps);
        this.agentService = new AgentService(orchestrator);
    }

    /** Runs one provider-neutral prompt synchronously. */
    public AgentResult run(AgentPromptRequest request) {
        return agentService.run(Objects.requireNonNull(request, "request"));
    }

    /** Runs one prompt and returns its final result together with its trace. */
    public AgentExecution runWithTrace(AgentPromptRequest request) {
        return agentService.runWithTrace(Objects.requireNonNull(request, "request"));
    }
}
