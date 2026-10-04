package com.easycode.agent.api;

import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;

import java.util.List;
import java.util.Optional;

/** Agent-facing read and invoke boundary for registered tools. */
public interface AgentToolAccess {
    List<ToolDefinition> listTools();

    Optional<ToolDefinition> resolveTool(String toolName);

    ToolResult invoke(ToolInvocation invocation);
}
