package io.github.everwynn.nova.ai.core.event;

/**
 * SSE 对话事件。
 *
 * <p>统一走 SSE 的 {@code message} 事件，前端按 {@code type} 分发，不依赖 SSE 的 {@code event:} 字段。
 * 除业务事件外，连接建立时发送 {@code connect}，心跳发送 {@code ping}。
 */
public final class ChatEvent {

    /** 连接就绪，前端可开始发送 */
    public static final String CONNECT = "connect";
    /** 心跳，前端忽略 */
    public static final String PING = "ping";
    /** 开始生成，前端新建回答气泡 */
    public static final String START = "start";
    /** 增量内容 */
    public static final String DELTA = "delta";
    /** 正常结束，content 为完整回答 */
    public static final String END = "end";
    /** 用户主动停止，content 为已生成部分 */
    public static final String STOPPED = "stopped";
    /** 生成失败，content 为错误信息 */
    public static final String ERROR = "error";

    private final String type;
    private final String content;
    private final long timestamp;

    private ChatEvent(String type, String content, long timestamp) {
        this.type = type;
        this.content = content;
        this.timestamp = timestamp;
    }

    public static ChatEvent connect() {
        return new ChatEvent(CONNECT, "connected", System.currentTimeMillis());
    }

    public static ChatEvent ping() {
        return new ChatEvent(PING, "heartbeat", System.currentTimeMillis());
    }

    public static ChatEvent start() {
        return new ChatEvent(START, null, System.currentTimeMillis());
    }

    public static ChatEvent delta(String content) {
        return new ChatEvent(DELTA, content, System.currentTimeMillis());
    }

    public static ChatEvent end(String content) {
        return new ChatEvent(END, content, System.currentTimeMillis());
    }

    public static ChatEvent stopped(String content) {
        return new ChatEvent(STOPPED, content, System.currentTimeMillis());
    }

    public static ChatEvent error(String content) {
        return new ChatEvent(ERROR, content, System.currentTimeMillis());
    }

    public String getType() {
        return type;
    }

    public String getContent() {
        return content;
    }

    public long getTimestamp() {
        return timestamp;
    }

    @Override
    public String toString() {
        return "ChatEvent{type='" + type + "', contentLength="
                + (content == null ? 0 : content.length()) + '}';
    }
}
