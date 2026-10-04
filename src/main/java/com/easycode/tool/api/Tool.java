package com.easycode.tool.api;

import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;

/** One executable Tool capability. */
public interface Tool {
    ToolDefinition definition();

    ToolResult execute(ToolInvocation invocation);
}
