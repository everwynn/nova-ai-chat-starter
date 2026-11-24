package io.github.everwynn.nova.ai.core.session;

import java.util.List;

/**
 * 会话存储 SPI。
 *
 * <p>默认实现是进程内存储；多实例部署时由使用方实现 Redis / DB 版本并注册为 Bean。
 * 实现需要保证：
 * <ul>
 *   <li>{@link #history} 返回快照，调用方修改不影响存储；</li>
 *   <li>{@link #append} 按会话串行写入，并按最大轮数裁剪；</li>
 *   <li>{@link #clear} 幂等。</li>
 * </ul>
 */
public interface ChatSessionStore {

    /** 按时间正序返回会话历史；会话不存在时返回空列表 */
    List<ChatMessage> history(String sessionId);

    /** 追加一条消息 */
    void append(String sessionId, ChatMessage message);

    /** 清空并删除会话，幂等 */
    void clear(String sessionId);

    /** 会话摘要列表，按最近活跃倒序 */
    List<SessionSummary> list();

    /** 当前会话数 */
    int sessionCount();
}
