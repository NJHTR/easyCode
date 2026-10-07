package com.easycode.language.runtime;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.model.EasyCodeInstructionTrace;
import com.easycode.language.model.EasyCodeInstructionTraceStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Collections;

/** Mutable state scoped to one easyCode program run. */
public final class EasyCodeExecutionContext {
    private final Map<String, Object> variables = new LinkedHashMap<>();
    private final StringBuilder consoleOutput = new StringBuilder();
    private final List<EasyCodeInstructionTrace> instructionTrace = new ArrayList<>();
    private int nextTraceSequence = 1;

    public void setVariable(String name, Object value) {
        validateName(name);
        variables.put(name, value);
    }

    public Object variable(String name) {
        validateName(name);
        if (!variables.containsKey(name)) {
            throw new IllegalArgumentException("unknown variable: " + name);
        }
        return variables.get(name);
    }

    public void print(Object value) {
        consoleOutput.append(String.valueOf(value));
    }

    public void println(Object value) {
        consoleOutput.append(String.valueOf(value)).append(System.lineSeparator());
    }

    public Map<String, Object> variablesSnapshot() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(variables));
    }

    public String consoleOutput() {
        return consoleOutput.toString();
    }

    /** Executes one instruction and records its terminal outcome in this run's trace. */
    public void execute(EasyCodeInstruction instruction) throws Exception {
        Objects.requireNonNull(instruction, "instruction");
        int sequence = nextTraceSequence++;
        Instant startedAt = Instant.now();
        try {
            instruction.execute(this);
            instructionTrace.add(new EasyCodeInstructionTrace(
                    sequence,
                    instruction.id(),
                    startedAt,
                    Instant.now(),
                    EasyCodeInstructionTraceStatus.SUCCEEDED,
                    ""));
        } catch (Exception exception) {
            instructionTrace.add(new EasyCodeInstructionTrace(
                    sequence,
                    instruction.id(),
                    startedAt,
                    Instant.now(),
                    EasyCodeInstructionTraceStatus.FAILED,
                    messageOf(exception)));
            throw exception;
        }
    }

    public List<EasyCodeInstructionTrace> instructionTraceSnapshot() {
        return instructionTrace.stream()
                .sorted(Comparator.comparingInt(EasyCodeInstructionTrace::sequence))
                .toList();
    }

    public String interpolate(String template) {
        Objects.requireNonNull(template, "template");
        StringBuilder result = new StringBuilder();
        int cursor = 0;
        while (cursor < template.length()) {
            int start = template.indexOf("${", cursor);
            if (start < 0) {
                result.append(template, cursor, template.length());
                break;
            }
            result.append(template, cursor, start);
            int end = template.indexOf('}', start + 2);
            if (end < 0) {
                throw new IllegalArgumentException("unterminated variable expression");
            }
            String name = template.substring(start + 2, end);
            result.append(String.valueOf(variable(name)));
            cursor = end + 1;
        }
        return result.toString();
    }

    private static void validateName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("variable name must not be blank");
        }
    }

    private static String messageOf(Exception exception) {
        return exception.getMessage() == null || exception.getMessage().isBlank()
                ? exception.getClass().getSimpleName() : exception.getMessage();
    }
}
