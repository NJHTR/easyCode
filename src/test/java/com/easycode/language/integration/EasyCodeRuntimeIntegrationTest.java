package com.easycode.language.integration;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.builtin.PrintInstruction;
import com.easycode.language.builtin.SetVariableInstruction;
import com.easycode.language.builtin.ConditionalInstruction;
import com.easycode.language.builtin.ComputeVariableInstruction;
import com.easycode.language.builtin.RepeatInstruction;
import com.easycode.language.expression.EasyCodeExpressions;
import com.easycode.language.model.EasyCodeExecutionResult;
import com.easycode.language.model.EasyCodeExecutionStatus;
import com.easycode.language.model.EasyCodeInstructionTrace;
import com.easycode.language.model.EasyCodeInstructionTraceStatus;
import com.easycode.language.model.EasyCodeProgram;
import com.easycode.language.parser.EasyCodeParseException;
import com.easycode.language.parser.EasyCodeSourceParser;
import com.easycode.language.runtime.EasyCodeExecutionContext;
import com.easycode.language.runtime.InProcessEasyCodeRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EasyCodeRuntimeIntegrationTest {
    @Test
    void executesInstructionsAndExposesConsoleAndVariables() {
        UUID programId = UUID.randomUUID();
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                programId,
                EasyCodeProgram.of(List.of(
                        new SetVariableInstruction("set-name", "name", "easyCode"),
                        PrintInstruction.line("print-name", "hello ${name}"))));

        assertTrue(result.succeeded(), result.toString());
        assertEquals(programId, result.programId());
        assertEquals("hello easyCode" + System.lineSeparator(), result.consoleOutput());
        assertEquals("easyCode", result.variables().get("name"));
        assertEquals(2, result.completedInstructionCount());
        assertEquals(List.of("set-name", "print-name"), result.instructionTrace().stream()
                .map(EasyCodeInstructionTrace::instructionId).toList());
        assertEquals(List.of(1, 2), result.instructionTrace().stream()
                .map(EasyCodeInstructionTrace::sequence).toList());
        assertTrue(result.instructionTrace().stream()
                .allMatch(trace -> trace.status() == EasyCodeInstructionTraceStatus.SUCCEEDED));
    }

    @Test
    void capturesInstructionFailureAndPartialState() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(
                        new SetVariableInstruction("set-count", "count", 3),
                        PrintInstruction.line("print-missing", "count=${missing}"))));

        assertEquals(EasyCodeExecutionStatus.FAILED, result.status());
        assertEquals("print-missing", result.failedInstructionId());
        assertEquals(1, result.completedInstructionCount());
        assertEquals(3, result.variables().get("count"));
        assertFalse(result.failureMessage().isBlank());
        assertEquals(EasyCodeInstructionTraceStatus.FAILED,
                result.instructionTrace().get(1).status());
    }

    @Test
    void customInstructionExtendsLanguageWithoutRuntimeChanges() {
        EasyCodeInstruction custom = new EasyCodeInstruction() {
            @Override
            public String id() {
                return "custom-double";
            }

            @Override
            public void execute(EasyCodeExecutionContext context) {
                context.setVariable("value", 21);
                context.println(Integer.parseInt(context.variable("value").toString()) * 2);
            }
        };

        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(), EasyCodeProgram.of(List.of(custom)));

        assertTrue(result.succeeded(), result.toString());
        assertEquals("42" + System.lineSeparator(), result.consoleOutput());
    }

    @Test
    void conditionalInstructionSelectsBranchFromProgramState() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(
                        new SetVariableInstruction("set-enabled", "enabled", true),
                        new ConditionalInstruction(
                                "if-enabled",
                                context -> Boolean.TRUE.equals(context.variable("enabled")),
                                EasyCodeProgram.of(List.of(
                                        PrintInstruction.line("print-on", "enabled"))),
                                EasyCodeProgram.of(List.of(
                                        PrintInstruction.line("print-off", "disabled")))))));

        assertTrue(result.succeeded(), result.toString());
        assertEquals("enabled" + System.lineSeparator(), result.consoleOutput());
        assertEquals(2, result.completedInstructionCount());
        assertEquals(List.of("set-enabled", "if-enabled", "print-on"),
                result.instructionTrace().stream().map(EasyCodeInstructionTrace::instructionId).toList());
    }

    @Test
    void conditionalExpressionFailureIdentifiesTheConditionalInstruction() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(new ConditionalInstruction(
                        "if-missing",
                        context -> Boolean.TRUE.equals(context.variable("missing")),
                        EasyCodeProgram.of(List.of()),
                        EasyCodeProgram.of(List.of())))));

        assertFalse(result.succeeded());
        assertEquals("if-missing", result.failedInstructionId());
        assertFalse(result.failureMessage().isBlank());
    }

    @Test
    void repeatInstructionExecutesItsBodyTheRequestedNumberOfTimes() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(
                        RepeatInstruction.times(
                                "repeat-greeting",
                                3,
                                EasyCodeProgram.of(List.of(
                                        PrintInstruction.line("print-greeting", "hello")))))));

        assertTrue(result.succeeded(), result.toString());
        assertEquals("hello" + System.lineSeparator()
                        + "hello" + System.lineSeparator()
                        + "hello" + System.lineSeparator(),
                result.consoleOutput());
        assertEquals(1, result.completedInstructionCount());
        assertEquals(List.of("repeat-greeting", "print-greeting", "print-greeting", "print-greeting"),
                result.instructionTrace().stream().map(EasyCodeInstructionTrace::instructionId).toList());
        assertEquals(List.of(1, 2, 3, 4), result.instructionTrace().stream()
                .map(EasyCodeInstructionTrace::sequence).toList());
    }

    @Test
    void repeatInstructionRejectsAnUnsafeCountAsTheInstructionFailure() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(new RepeatInstruction(
                        "repeat-invalid",
                        ignored -> -1,
                        EasyCodeProgram.of(List.of())))));

        assertFalse(result.succeeded());
        assertEquals("repeat-invalid", result.failedInstructionId());
        assertTrue(result.failureMessage().contains("negative"));
        assertEquals(List.of("repeat-invalid"), result.instructionTrace().stream()
                .map(EasyCodeInstructionTrace::instructionId).toList());
    }

    @Test
    void composedExpressionsReadAndComputeProgramVariables() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(
                        new SetVariableInstruction("set-count", "count", 2),
                        new ComputeVariableInstruction(
                                "compute-total",
                                "total",
                                EasyCodeExpressions.add(
                                        EasyCodeExpressions.variable("count", Integer.class),
                                        EasyCodeExpressions.constant(3))),
                        new ConditionalInstruction(
                                "if-total",
                                EasyCodeExpressions.equalTo(
                                        EasyCodeExpressions.variable("total", Integer.class),
                                        EasyCodeExpressions.constant(5)),
                                EasyCodeProgram.of(List.of(PrintInstruction.line("print-ok", "ok"))),
                                EasyCodeProgram.of(List.of(PrintInstruction.line("print-bad", "bad")))))));

        assertTrue(result.succeeded(), result.toString());
        assertEquals(5, result.variables().get("total"));
        assertEquals("ok" + System.lineSeparator(), result.consoleOutput());
        assertEquals(3, result.completedInstructionCount());
    }

    @Test
    void nestedFailureRecordsChildAndParentInstructionOutcomes() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(),
                EasyCodeProgram.of(List.of(new ConditionalInstruction(
                        "if-fails",
                        EasyCodeExpressions.constant(true),
                        EasyCodeProgram.of(List.of(PrintInstruction.line("print-missing", "${missing}"))),
                        EasyCodeProgram.of(List.of())))));

        assertFalse(result.succeeded());
        assertEquals("if-fails", result.failedInstructionId());
        assertEquals(List.of("if-fails", "print-missing"), result.instructionTrace().stream()
                .map(EasyCodeInstructionTrace::instructionId).toList());
        assertEquals(EasyCodeInstructionTraceStatus.FAILED, result.instructionTrace().get(0).status());
        assertEquals(EasyCodeInstructionTraceStatus.FAILED, result.instructionTrace().get(1).status());
    }

    @Test
    void executionTraceIsImmutable() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(), EasyCodeProgram.of(List.of(PrintInstruction.line("print", "ok"))));

        assertThrows(UnsupportedOperationException.class,
                () -> result.instructionTrace().clear());
    }

    @Test
    void parsesAndExecutesEasyCodeSource() {
        EasyCodeExecutionResult result = new InProcessEasyCodeRuntime().execute(
                UUID.randomUUID(), new EasyCodeSourceParser().parse(""
                        + "# a small easyCode source program\n"
                        + "set name = \"easyCode\"\n"
                        + "println \"hello ${name}\"\n"
                        + "print !"));

        assertTrue(result.succeeded(), result.toString());
        assertEquals("hello easyCode" + System.lineSeparator() + "!", result.consoleOutput());
        assertEquals("easyCode", result.variables().get("name"));
        assertEquals(3, result.completedInstructionCount());
    }

    @Test
    void parserReportsSourceLineForInvalidInstruction() {
        EasyCodeParseException exception = assertThrows(EasyCodeParseException.class,
                () -> new EasyCodeSourceParser().parse("set count = 1\nunknown value"));

        assertTrue(exception.getMessage().contains("line 2"));
    }
}
