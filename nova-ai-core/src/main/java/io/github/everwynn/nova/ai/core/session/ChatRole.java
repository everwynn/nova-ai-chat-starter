package io.github.everwynn.nova.ai.core.session;

import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 消息角色。序列化为 OpenAI 协议使用的小写形式。
 */
public enum ChatRole {

    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String wireName;

    ChatRole(String wireName) {
        this.wireName = wireName;
    }

    @JsonValue
    public String wireName() {
        return wireName;
    }
}
