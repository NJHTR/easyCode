package com.easycode.canvas.execution.builtin;

import com.easycode.canvas.api.CanvasNodeCatalog;

/** Descriptions for the small built-in node library. */
public final class CanvasBuiltinNodes {
    private CanvasBuiltinNodes() {
    }

    public static CanvasNodeCatalog catalog() {
        return CanvasBuiltinLibrary.registry().catalog();
    }
}
