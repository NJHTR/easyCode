package com.easycode.language.builtin;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.runtime.EasyCodeExecutionContext;

import java.util.Objects;

/** Built-in instruction that writes one value into the current program state. */
public record SetVariableInstruction(String id, String name, Object value)
        implements EasyCodeInstruction {
    public SetVariableInstruction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instruction id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
    }

    @Override
    public void execute(EasyCodeExecutionContext context) {
        context.setVariable(name, value);
    }
}
