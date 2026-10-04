package com.easycode.llm.adapter.springai;

import com.easycode.llm.api.LlmProvider;
import com.easycode.llm.exception.LlmException;
import com.easycode.llm.model.LlmMessage;
import com.easycode.llm.model.LlmRequest;
import com.easycode.llm.model.LlmResponse;
import com.easycode.llm.model.LlmToolCall;
import com.easycode.tool.model.ToolDefinition;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.DefaultToolDefinition;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Spring AI adapter for one synchronous, non-executing model call. */
public final class SpringAiLlmProvider implements LlmProvider {
    private final ChatModel chatModel;

    public SpringAiLlmProvider(ChatModel chatModel) {
        this.chatModel = Objects.requireNonNull(chatModel, "chatModel");
    }

    @Override
    public LlmResponse generate(LlmRequest request) throws LlmException {
        Objects.requireNonNull(request, "request");
        try {
            Prompt prompt = new Prompt(toMessages(request.messages()), toOptions(request));
            ChatResponse response = chatModel.call(prompt);
            return toResponse(response);
        } catch (RuntimeException exception) {
            throw new LlmException("Spring AI LLM call failed", exception);
        }
    }

    private ChatOptions toOptions(LlmRequest request) {
        List<ToolCallback> callbacks = request.tools().stream()
                .map(DescriptorOnlyToolCallback::new)
                .map(callback -> (ToolCallback) callback)
                .toList();

        ChatOptions modelOptions = chatModel.getOptions();
        if (modelOptions instanceof ToolCallingChatOptions toolCallingOptions) {
            return toolCallingOptions.mutate()
                    .model(request.model())
                    .toolCallbacks(callbacks)
                    .build();
        }

        return ToolCallingChatOptions.builder()
                .model(request.model())
                .toolCallbacks(callbacks)
                .build();
    }

    private static List<Message> toMessages(List<LlmMessage> messages) {
        return messages.stream().map(SpringAiLlmProvider::toMessage).toList();
    }

    private static Message toMessage(LlmMessage message) {
        return switch (message.role()) {
            case SYSTEM -> new SystemMessage(message.content());
            case USER -> new UserMessage(message.content());
            case ASSISTANT -> new AssistantMessage(message.content());
            case TOOL -> ToolResponseMessage.builder()
                    .responses(List.of(new ToolResponseMessage.ToolResponse(
                            "", "", message.content())))
                    .build();
        };
    }

    private static LlmResponse toResponse(ChatResponse response) {
        if (response == null) {
            throw new IllegalArgumentException("Spring AI returned a null response");
        }
        Generation generation = response.getResult();
        if (generation == null || generation.getOutput() == null) {
            throw new IllegalArgumentException("Spring AI response contained no generation");
        }

        AssistantMessage output = generation.getOutput();
        List<LlmToolCall> toolCalls = output.getToolCalls().stream()
                .map(toolCall -> new LlmToolCall(
                        toCallId(toolCall.id()), toolCall.name(), toolCall.arguments()))
                .toList();
        return new LlmResponse(output.getText(), toolCalls);
    }

    private static UUID toCallId(String callId) {
        if (callId == null || callId.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(callId);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(callId.getBytes(StandardCharsets.UTF_8));
        }
    }

    /**
     * Spring AI requires a ToolCallback to carry a tool definition in options.
     * This bridge deliberately refuses execution because the Agent owns that boundary.
     */
    private record DescriptorOnlyToolCallback(
            org.springframework.ai.tool.definition.ToolDefinition definition)
            implements ToolCallback {

        private DescriptorOnlyToolCallback(ToolDefinition definition) {
            this(new DefaultToolDefinition(
                    definition.name(), definition.description(), definition.inputSchema()));
        }

        @Override
        public org.springframework.ai.tool.definition.ToolDefinition getToolDefinition() {
            return definition;
        }

        @Override
        public String call(String arguments) {
            throw new UnsupportedOperationException(
                    "SpringAiLlmProvider does not execute easyCode Tools");
        }
    }
}
