package com.easycode.language.builtin;

import com.easycode.language.api.EasyCodeExpression;
import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.runtime.EasyCodeExecutionContext;

import java.util.Objects;

/** Assigns a variable from a value computed against the current program state. */
public record ComputeVariableInstruction(
        String id,
        String name,
        EasyCodeExpression<?> expression) implements EasyCodeInstruction {
    public ComputeVariableInstruction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instruction id must not be blank");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
        Objects.requireNonNull(expression, "expression");
    }

    @Override
    public void execute(EasyCodeExecutionContext context) throws Exception {
        context.setVariable(name, expression.evaluate(context));
    }
}
