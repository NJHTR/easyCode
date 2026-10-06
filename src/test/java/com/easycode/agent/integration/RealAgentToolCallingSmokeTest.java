package com.easycode.agent.integration;

import com.easycode.agent.application.LocalAgentApplication;
import com.easycode.agent.model.AgentPromptRequest;
import com.easycode.agent.model.AgentResult;
import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.adapter.springai.SpringAiLlmProvider;
import com.easycode.llm.adapter.springai.openai.OpenAiChatModelFactory;
import com.easycode.llm.adapter.springai.openai.OpenAiCompatibleModelConfig;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;
import com.easycode.tool.api.Tool;
import com.easycode.tool.api.ToolRegistry;
import com.easycode.tool.model.ToolDefinition;
import com.easycode.tool.model.ToolFailureReason;
import com.easycode.tool.model.ToolInvocation;
import com.easycode.tool.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.fail;

/** Explicit external end-to-end Agent Tool Calling smoke test. */
@Tag("real-llm")
class RealAgentToolCallingSmokeTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void realModelCallsAddToolAndReceivesItsResult() {
        assumeTrue(isEnabled(), "REAL_AGENT_TOOL_CALLING_SMOKE=NOT_RUN");
        String apiKey = environment("EASYCODE_LLM_API_KEY");
        String model = environment("EASYCODE_LLM_MODEL");
        assumeTrue(apiKey != null && model != null,
                "REAL_AGENT_TOOL_CALLING_SMOKE=PENDING_RETRY: provider configuration missing");

        String baseUrl = environment("EASYCODE_LLM_BASE_URL");
        if (baseUrl == null) {
            baseUrl = OpenAiCompatibleModelConfig.DEFAULT_BASE_URL;
        }

        AddTool addTool = new AddTool();
        ToolRegistry registry = new ToolRegistry();
        registry.register(addTool);
        RecordingProvider provider;
        try {
            provider = new RecordingProvider(new SpringAiLlmProvider(
                    OpenAiChatModelFactory.create(
                            new OpenAiCompatibleModelConfig(baseUrl, apiKey, model))));
        } catch (IllegalArgumentException exception) {
            fail("CONFIGURATION_ERROR");
            return;
        }

        AgentResult result = LocalAgentApplication.openSession(
                provider, registry, 5).run(AgentPromptRequest.create(model,
                        List.of(LlmMessage.user(
                                "Use the add tool to calculate 2 + 3. "
                                        + "Do not calculate it yourself. You must call the add tool. "
                                        + "After receiving the tool result, reply with the final result."))));

        if (provider.responses.isEmpty() || provider.responses.get(0).toolCalls().isEmpty()) {
            fail("NO_TOOL_CALL");
        }
        if (addTool.invocationCount != 1) {
            fail("TOOL_NOT_INVOKED");
        }
        if (addTool.failureReason != null) {
            fail(addTool.failureReason == ToolFailureReason.INVALID_INVOCATION
                    ? "INVALID_TOOL_ARGUMENTS" : "TOOL_EXECUTION_ERROR");
        }
        assertTrue(addTool.lastA == 2 && addTool.lastB == 3, "INVALID_TOOL_ARGUMENTS");
        assertTrue(addTool.lastOutput.equals("5"), "TOOL_EXECUTION_ERROR");

        if (provider.requests.size() < 2) {
            fail("MODEL_ERROR");
        }
        List<LlmMessage> secondMessages = provider.requests.get(1).messages();
        assertTrue(secondMessages.stream().anyMatch(message ->
                message.role() == com.easycode.llm.model.LlmMessageRole.TOOL
                        && "add".equals(message.toolName())
                        && "5".equals(message.content())), "TOOL_RESULT_FEEDBACK");
        assertTrue(result.succeeded(), "MODEL_ERROR status=" + result.run().status()
                + " failure=" + result.failureReason()
                + " providerCalls=" + provider.requests.size()
                + " responses=" + provider.responses.size()
                + " messageType=" + result.message().getClass().getName());
        assertNotNull(result.message(), "FINAL_RESPONSE_EMPTY");
        assertFalse(result.message().trim().isEmpty(), "FINAL_RESPONSE_EMPTY");
    }

    private static boolean isEnabled() {
        return "true".equalsIgnoreCase(environment("EASYCODE_REAL_LLM_TEST"));
    }

    private static String environment(String name) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static final class RecordingProvider implements LlmProvider {
        private final LlmProvider delegate;
        private final List<LlmRequest> requests = new ArrayList<>();
        private final List<LlmResponse> responses = new ArrayList<>();

        private RecordingProvider(LlmProvider delegate) {
            this.delegate = delegate;
        }

        @Override
        public LlmResponse generate(LlmRequest request) throws LlmException {
            requests.add(request);
            LlmResponse response = delegate.generate(request);
            responses.add(response);
            return response;
        }
    }

    static final class AddTool implements Tool {
        private static final ToolDefinition DEFINITION = new ToolDefinition(
                "add", "Add two integers and return the result.",
                "{\"type\":\"object\",\"properties\":{\"a\":{\"type\":\"integer\"},\"b\":{\"type\":\"integer\"}},\"required\":[\"a\",\"b\"]}");
        int invocationCount;
        int lastA;
        int lastB;
        String lastOutput;
        ToolFailureReason failureReason;

        @Override
        public ToolDefinition definition() {
            return DEFINITION;
        }

        @Override
        public ToolResult execute(ToolInvocation invocation) {
            invocationCount++;
            try {
                JsonNode input = JSON.readTree(invocation.input());
                if (!input.isObject() || !input.has("a") || !input.has("b")
                        || !input.get("a").isIntegralNumber()
                        || !input.get("b").isIntegralNumber()
                        || !input.get("a").canConvertToInt()
                        || !input.get("b").canConvertToInt()) {
                    failureReason = ToolFailureReason.INVALID_INVOCATION;
                    return ToolResult.failure(invocation.callId(), failureReason,
                            "a and b must be integers");
                }
                lastA = input.get("a").intValue();
                lastB = input.get("b").intValue();
                lastOutput = Integer.toString(lastA + lastB);
                return ToolResult.success(invocation.callId(), lastOutput);
            } catch (Exception exception) {
                failureReason = ToolFailureReason.INVALID_INVOCATION;
                return ToolResult.failure(invocation.callId(), failureReason,
                        "invalid JSON arguments");
            }
        }
    }
}
