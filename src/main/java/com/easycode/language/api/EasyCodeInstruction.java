package com.easycode.language.api;

import com.easycode.language.runtime.EasyCodeExecutionContext;

/** One executable instruction in an easyCode program. */
public interface EasyCodeInstruction {
    String id();

    void execute(EasyCodeExecutionContext context) throws Exception;
}
