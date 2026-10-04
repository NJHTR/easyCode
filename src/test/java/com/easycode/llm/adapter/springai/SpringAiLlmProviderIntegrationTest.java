package com.easycode.llm.adapter.springai;

import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmMessageRole;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;
import com.easycode.tool.model.ToolDefinition;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpringAiLlmProviderIntegrationTest {
    @Test
    void mapsPlainTextMessagesAndModelWithoutExecutingAnything() throws LlmException {
        RecordingChatModel chatModel = new RecordingChatModel(
                prompt -> textResponse("Hello"));
        SpringAiLlmProvider provider = new SpringAiLlmProvider(chatModel);

        LlmResponse response = provider.generate(LlmRequest.of(
                "fake-model",
                List.of(
                        LlmMessage.system("system"),
                        LlmMessage.user("user"),
                        LlmMessage.assistant("assistant"),
                        LlmMessage.tool("tool result"))));

        assertEquals("Hello", response.content());
        assertTrue(response.toolCalls().isEmpty());
        assertEquals("fake-model", options(chatModel.lastPrompt()).getModel());

        List<Message> messages = chatModel.lastPrompt().getInstructions();
        assertEquals(List.of(
                MessageType.SYSTEM,
                MessageType.USER,
                MessageType.ASSISTANT,
                MessageType.TOOL), messages.stream().map(Message::getMessageType).toList());
        assertEquals("system", messages.get(0).getText());
        assertEquals("user", messages.get(1).getText());
        assertEquals("assistant", messages.get(2).getText());
    }

    @Test
    void projectsToolDefinitionAsDescriptorOnlyCallback() throws LlmException {
        RecordingChatModel chatModel = new RecordingChatModel(
                prompt -> textResponse("model answer"));
        SpringAiLlmProvider provider = new SpringAiLlmProvider(chatModel);
        ToolDefinition definition = new ToolDefinition(
                "echo", "Echo input", "{\"type\":\"object\"}");

        provider.generate(LlmRequest.of(
                "fake-model", List.of(LlmMessage.user("hello")), List.of(definition)));

        ToolCallingChatOptions options = options(chatModel.lastPrompt());
        assertEquals(1, options.getToolCallbacks().size());
        org.springframework.ai.tool.definition.ToolDefinition springDefinition =
                options.getToolCallbacks().get(0).getToolDefinition();
        assertEquals("echo", springDefinition.name());
        assertEquals("Echo input", springDefinition.description());
        assertEquals("{\"type\":\"object\"}", springDefinition.inputSchema());
        assertThrows(UnsupportedOperationException.class,
                () -> options.getToolCallbacks().get(0).call("{}"));
    }

    @Test
    void mapsSpringAiToolCallToLlmToolCallWithoutInvokingIt() throws LlmException {
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        "call-1", "function", "echo", "{\"value\":\"hello\"}")))
                .build();
        RecordingChatModel chatModel = new RecordingChatModel(
                prompt -> new ChatResponse(List.of(new Generation(assistantMessage))));

        LlmResponse response = new SpringAiLlmProvider(chatModel).generate(
                LlmRequest.of("fake-model", List.of(LlmMessage.user("call echo"))));

        assertEquals(1, response.toolCalls().size());
        assertEquals(UUID.nameUUIDFromBytes("call-1".getBytes(StandardCharsets.UTF_8)),
                response.toolCalls().get(0).callId());
        assertEquals("echo", response.toolCalls().get(0).toolName());
        assertEquals("{\"value\":\"hello\"}", response.toolCalls().get(0).arguments());
    }

    @Test
    void normalizesChatModelFailureToLlmException() {
        RuntimeException failure = new IllegalStateException("provider unavailable");
        RecordingChatModel chatModel = new RecordingChatModel(prompt -> {
            throw failure;
        });

        LlmException exception = assertThrows(LlmException.class, () ->
                new SpringAiLlmProvider(chatModel).generate(
                        LlmRequest.of("fake-model", List.of(LlmMessage.user("hello")))));

        assertEquals("Spring AI LLM call failed", exception.getMessage());
        assertEquals(failure, exception.getCause());
    }

    private static ToolCallingChatOptions options(Prompt prompt) {
        return assertInstanceOf(ToolCallingChatOptions.class, prompt.getOptions());
    }

    private static ChatResponse textResponse(String content) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(content))));
    }

    private static final class RecordingChatModel implements ChatModel {
        private final Function<Prompt, ChatResponse> responder;
        private Prompt lastPrompt;

        private RecordingChatModel(Function<Prompt, ChatResponse> responder) {
            this.responder = responder;
        }

        @Override
        public ChatResponse call(Prompt prompt) {
            lastPrompt = prompt;
            return responder.apply(prompt);
        }

        private Prompt lastPrompt() {
            return lastPrompt;
        }
    }
}
