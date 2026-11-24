package io.github.everwynn.nova.ai.starter.web;

/**
 * 发送消息请求体。
 */
public class ChatSendRequest {

    private String clientId;
    private String sessionId;
    private String message;

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
