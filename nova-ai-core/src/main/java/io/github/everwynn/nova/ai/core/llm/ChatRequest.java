package io.github.everwynn.nova.ai.core.llm;

import io.github.everwynn.nova.ai.core.session.ChatMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次流式生成请求：系统提示词已由调用方拼进 messages。
 */
public final class ChatRequest {

    private final String model;
    private final List<ChatMessage> messages;

    public ChatRequest(String model, List<ChatMessage> messages) {
        this.model = model == null ? "" : model;
        this.messages = messages == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(messages));
    }

    public String getModel() {
        return model;
    }

    public List<ChatMessage> getMessages() {
        return messages;
    }
}
