package com.easycode.language.integration;

import com.easycode.language.api.EasyCodeInstruction;
import com.easycode.language.builtin.PrintInstruction;
import com.easycode.language.builtin.SetVariableInstruction;
import com.easycode.language.builtin.ConditionalInstruction;
import com.easycode.language.model.EasyCodeExecutionResult;
import com.easycode.language.model.EasyCodeExecutionStatus;
import com.easycode.language.model.EasyCodeProgram;
import com.easycode.language.runtime.EasyCodeExecutionContext;
import com.easycode.language.runtime.InProcessEasyCodeRuntime;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
