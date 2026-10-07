package com.easycode.language.builtin;

import com.easycode.language.api.EasyCodeExpression;
import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.model.EasyCodeProgram;
import com.easycode.language.runtime.EasyCodeExecutionContext;

import java.util.Objects;

/** Repeats a language instruction block a bounded number of times. */
public record RepeatInstruction(
        String id,
        EasyCodeExpression<Integer> repetitions,
        EasyCodeProgram body) implements EasyCodeInstruction {
    private static final int MAX_REPETITIONS = 100_000;

    public RepeatInstruction {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("instruction id must not be blank");
        }
        Objects.requireNonNull(repetitions, "repetitions");
        Objects.requireNonNull(body, "body");
    }

    public static RepeatInstruction times(String id, int repetitions, EasyCodeProgram body) {
        return new RepeatInstruction(id, ignored -> repetitions, body);
    }

    @Override
    public void execute(EasyCodeExecutionContext context) throws Exception {
        Integer count = repetitions.evaluate(context);
        if (count == null) {
            throw new IllegalArgumentException("repeat expression returned null");
        }
        if (count < 0) {
            throw new IllegalArgumentException("repeat count must not be negative");
        }
        if (count > MAX_REPETITIONS) {
            throw new IllegalArgumentException("repeat count exceeds " + MAX_REPETITIONS);
        }
        for (int iteration = 0; iteration < count; iteration++) {
            for (EasyCodeInstruction instruction : body.instructions()) {
                instruction.execute(context);
            }
        }
    }
}
