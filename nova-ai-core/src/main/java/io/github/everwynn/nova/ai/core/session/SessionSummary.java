package io.github.everwynn.nova.ai.core.session;

/**
 * 会话列表摘要，供前端的会话面板使用。
 */
public final class SessionSummary {

    private final String sessionId;
    private final String preview;
    private final int messageCount;
    private final long lastActiveAt;

    public SessionSummary(String sessionId, String preview, int messageCount, long lastActiveAt) {
        this.sessionId = sessionId;
        this.preview = preview == null ? "" : preview;
        this.messageCount = messageCount;
        this.lastActiveAt = lastActiveAt;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getPreview() {
        return preview;
    }

    public int getMessageCount() {
        return messageCount;
    }

    public long getLastActiveAt() {
        return lastActiveAt;
    }
}
