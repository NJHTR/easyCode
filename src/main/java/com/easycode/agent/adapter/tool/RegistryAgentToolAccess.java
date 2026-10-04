package com.easycode.agent.adapter.tool;

import com.easycode.agent.api.AgentToolAccess;
import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exposes only discovery and invocation over an application-owned ToolRegistry. */
public final class RegistryAgentToolAccess implements AgentToolAccess {
    private final ToolRegistry registry;

    public RegistryAgentToolAccess(ToolRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    @Override
    public List<ToolDefinition> listTools() {
        return registry.list();
    }

    @Override
    public Optional<ToolDefinition> resolveTool(String toolName) {
        return registry.resolve(toolName).map(Tool::definition);
    }

    @Override
    public ToolResult invoke(ToolInvocation invocation) {
        return registry.invoke(invocation);
    }
}
