package com.easycode.canvas.execution;

import com.easycode.canvas.model.CanvasNode;

/** Handles one registered canvas node type without knowing graph orchestration. */
@FunctionalInterface
public interface CanvasNodeExecutor {
    void execute(CanvasNode node, CanvasNodeExecutionContext context) throws Exception;
}
