package io.github.everwynn.nova.ai.core.session;

import java.util.Objects;

/**
 * 一条会话消息。历史存储只保留角色与内容，不带状态标记。
 */
public final class ChatMessage {

    private final ChatRole role;
    private final String content;

    public ChatMessage(ChatRole role, String content) {
        this.role = Objects.requireNonNull(role, "role");
        this.content = content == null ? "" : content;
    }

    public static ChatMessage system(String content) {
        return new ChatMessage(ChatRole.SYSTEM, content);
    }

    public static ChatMessage user(String content) {
        return new ChatMessage(ChatRole.USER, content);
    }

    public static ChatMessage assistant(String content) {
        return new ChatMessage(ChatRole.ASSISTANT, content);
    }

    public ChatRole getRole() {
        return role;
    }

    public String getContent() {
        return content;
    }

    @Override
    public String toString() {
        return role.wireName() + ":" + (content.length() > 40 ? content.substring(0, 40) + "..." : content);
    }
}
