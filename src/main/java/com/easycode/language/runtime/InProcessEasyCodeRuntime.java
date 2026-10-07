package com.easycode.language.runtime;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.api.EasyCodeRuntime;
import com.easycode.language.model.EasyCodeExecutionResult;
import com.easycode.language.model.EasyCodeExecutionStatus;
import com.easycode.language.model.EasyCodeProgram;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Small in-process interpreter for trusted easyCode instructions. */
public final class InProcessEasyCodeRuntime implements EasyCodeRuntime {
    @Override
    public EasyCodeExecutionResult execute(UUID programId, EasyCodeProgram program) {
        Objects.requireNonNull(programId, "programId");
        Objects.requireNonNull(program, "program");
        Instant startedAt = Instant.now();
        EasyCodeExecutionContext context = new EasyCodeExecutionContext();
        int completed = 0;
        for (EasyCodeInstruction instruction : program.instructions()) {
            try {
                instruction.execute(context);
                completed++;
            } catch (Exception exception) {
                return new EasyCodeExecutionResult(
                        programId,
                        EasyCodeExecutionStatus.FAILED,
                        context.consoleOutput(),
                        context.variablesSnapshot(),
                        completed,
                        instruction.id(),
                        messageOf(exception),
                        Duration.between(startedAt, Instant.now()));
            }
        }
        return new EasyCodeExecutionResult(
                programId,
                EasyCodeExecutionStatus.SUCCEEDED,
                context.consoleOutput(),
                context.variablesSnapshot(),
                completed,
                null,
                "",
                Duration.between(startedAt, Instant.now()));
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
