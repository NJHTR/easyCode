package com.easycode.language.builtin;

import com.easycode.language.api.EasyCodeExpression;
import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.model.EasyCodeProgram;
import com.easycode.language.runtime.EasyCodeExecutionContext;

import java.util.Objects;

/** Executes one of two instruction branches based on a language expression. */
public record ConditionalInstruction(
        String id,
        EasyCodeExpression<Boolean> condition,
        EasyCodeProgram whenTrue,
        EasyCodeProgram whenFalse) implements EasyCodeInstruction {
    public ConditionalInstruction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instruction id must not be blank");
        }
        Objects.requireNonNull(condition, "condition");
        Objects.requireNonNull(whenTrue, "whenTrue");
        Objects.requireNonNull(whenFalse, "whenFalse");
    }

    @Override
    public void execute(EasyCodeExecutionContext context) throws Exception {
        Boolean result = condition.evaluate(context);
        if (result == null) {
            throw new IllegalArgumentException("conditional expression returned null");
        }
        EasyCodeProgram branch = result ? whenTrue : whenFalse;
        for (EasyCodeInstruction instruction : branch.instructions()) {
            instruction.execute(context);
        }
    }
}
