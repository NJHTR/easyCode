package com.easycode.language.api;

import com.easycode.language.model.EasyCodeExecutionResult;
import com.easycode.language.model.EasyCodeProgram;

import java.util.UUID;

/** Executes easyCode instructions synchronously in a controlled context. */
public interface EasyCodeRuntime {
    EasyCodeExecutionResult execute(UUID programId, EasyCodeProgram program);
}
