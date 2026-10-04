package com.easycode.tool.api;

import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolFailureReason;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** In-process registration, resolution, and failure boundary for Tools. */
public final class ToolRegistry {
    private final Map<String, Tool> tools = new LinkedHashMap<>();

    public void register(Tool tool) {
        Objects.requireNonNull(tool, "tool");
        ToolDefinition definition = Objects.requireNonNull(
                tool.definition(), "tool.definition()");
        synchronized (tools) {
            if (tools.putIfAbsent(definition.name(), tool) != null) {
                throw new IllegalArgumentException(
                        "tool already registered: " + definition.name());
            }
        }
    }

    public Optional<Tool> resolve(String toolName) {
        if (toolName == null || toolName.isBlank()) {
            return Optional.empty();
        }
        synchronized (tools) {
            return Optional.ofNullable(tools.get(toolName));
        }
    }

    public List<ToolDefinition> list() {
        synchronized (tools) {
            List<ToolDefinition> definitions = new ArrayList<>();
            for (Tool tool : tools.values()) {
                definitions.add(tool.definition());
            }
            return Collections.unmodifiableList(definitions);
        }
    }

    public ToolResult invoke(ToolInvocation invocation) {
        Objects.requireNonNull(invocation, "invocation");
        Tool tool = resolve(invocation.toolName()).orElse(null);
        if (tool == null) {
            return ToolResult.failure(
                    invocation.callId(),
                    ToolFailureReason.TOOL_NOT_FOUND,
                    "tool not found: " + invocation.toolName());
        }
        try {
            ToolResult result = tool.execute(invocation);
            if (result == null) {
                return ToolResult.failure(
                        invocation.callId(),
                        ToolFailureReason.INTERNAL_ERROR,
                        "tool returned no result");
            }
            return result;
        } catch (RuntimeException exception) {
            String message = exception.getMessage();
            return ToolResult.failure(
                    invocation.callId(),
                    ToolFailureReason.EXECUTION_FAILURE,
                    message == null || message.isBlank()
                            ? "tool execution failed"
                            : message);
        }
    }
}
