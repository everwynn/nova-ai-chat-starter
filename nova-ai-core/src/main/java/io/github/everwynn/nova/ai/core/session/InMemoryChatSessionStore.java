package io.github.everwynn.nova.ai.core.session;

import io.github.everwynn.nova.ai.core.config.NovaAiSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 进程内会话存储（单实例）。
 *
 * <p>三重容量约束：
 * <ol>
 *   <li>单会话按最大轮数裁剪最旧消息；</li>
 *   <li>空闲超过 TTL 的会话定时回收；</li>
 *   <li>会话总数超过上限时淘汰最久未活跃的会话。</li>
 * </ol>
 * 多实例部署时内存不共享，需替换为 Redis / DB 实现。
 */
public class InMemoryChatSessionStore implements ChatSessionStore, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(InMemoryChatSessionStore.class);

    /** 过期会话扫描间隔（分钟） */
    private static final long SWEEP_INTERVAL_MINUTES = 10L;

    /** 列表摘要截断长度 */
    private static final int SUMMARY_LENGTH = 30;

    private final int maxTurns;
    private final long ttlMillis;
    private final int maxSessions;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService sweeper;
    private final AtomicBoolean started = new AtomicBoolean(false);

    public InMemoryChatSessionStore(NovaAiSettings settings) {
        this.maxTurns = Math.max(1, settings.getMaxTurns());
        this.ttlMillis = TimeUnit.MINUTES.toMillis(Math.max(1L, settings.getTtlMinutes()));
        this.maxSessions = Math.max(1, settings.getMaxSessions());
        this.sweeper = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "nova-chat-session-sweeper");
            t.setDaemon(true);
            return t;
        });
    }

    /** 启动过期会话扫描；重复调用无副作用 */
    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        sweeper.scheduleWithFixedDelay(this::sweepExpired,
                SWEEP_INTERVAL_MINUTES, SWEEP_INTERVAL_MINUTES, TimeUnit.MINUTES);
    }

    @Override
    public void close() {
        sweeper.shutdownNow();
    }

    @Override
    public List<ChatMessage> history(String sessionId) {
        Session session = sessions.get(sessionId);
        if (session == null) {
            return new ArrayList<>();
        }
        session.touch();
        return new ArrayList<>(session.messages);
    }

    @Override
    public void append(String sessionId, ChatMessage message) {
        // compute 内完成写入、摘要与裁剪：与过期回收互斥，避免会话被并发移除后写入丢失
        sessions.compute(sessionId, (key, existing) -> {
            Session current = existing == null ? new Session() : existing;
            current.messages.addLast(message);
            current.messageCount++;
            if (current.preview.isEmpty() && message.getRole() == ChatRole.USER) {
                current.preview = summarize(message.getContent());
            }
            current.touch();
            trim(current);
            return current;
        });
        evictIfTooMany();
    }

    @Override
    public void clear(String sessionId) {
        sessions.remove(sessionId);
    }

    @Override
    public List<SessionSummary> list() {
        List<SessionSummary> result = new ArrayList<>(sessions.size());
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            // 直接读增量维护的摘要与计数，避免每次列会话都复制全部消息
            result.add(new SessionSummary(entry.getKey(), session.preview,
                    session.messageCount, session.lastActiveAt));
        }
        result.sort((a, b) -> Long.compare(b.getLastActiveAt(), a.getLastActiveAt()));
        return result;
    }

    @Override
    public int sessionCount() {
        return sessions.size();
    }

    /** 摘要截断：会话标题只保留前 SUMMARY_LENGTH 个字符 */
    private static String summarize(String content) {
        String text = content == null ? "" : content.trim();
        return text.length() <= SUMMARY_LENGTH
                ? text : text.substring(0, SUMMARY_LENGTH) + "…";
    }

    /** 按最大轮数裁剪：1 轮 = 用户提问 + 模型回答两条消息 */
    private void trim(Session session) {
        int limit = maxTurns * 2;
        while (session.messageCount > limit) {
            if (session.messages.pollFirst() == null) {
                // 计数与队列不一致时兜底，避免空转
                session.messageCount = 0;
                return;
            }
            session.messageCount--;
        }
    }

    /** 会话总数超限时，淘汰最久未活跃的会话 */
    private void evictIfTooMany() {
        if (sessions.size() <= maxSessions) {
            return;
        }
        synchronized (this) {
            int evicted = 0;
            int guard = sessions.size() + 1;
            while (sessions.size() > maxSessions && guard-- > 0) {
                String oldestKey = null;
                long oldestAt = Long.MAX_VALUE;
                for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                    long activeAt = entry.getValue().lastActiveAt;
                    if (activeAt < oldestAt) {
                        oldestAt = activeAt;
                        oldestKey = entry.getKey();
                    }
                }
                if (oldestKey == null) {
                    break;
                }
                Session victim = sessions.get(oldestKey);
                if (victim != null && victim.lastActiveAt == oldestAt
                        && sessions.remove(oldestKey, victim)) {
                    evicted++;
                }
            }
            if (evicted > 0) {
                logger.info("会话数超过上限 {}, 淘汰最久未活跃会话 {} 个，当前会话数 {}",
                        maxSessions, evicted, sessions.size());
            }
        }
    }

    private void sweepExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (now - entry.getValue().lastActiveAt > ttlMillis
                    && sessions.remove(entry.getKey(), entry.getValue())) {
                removed++;
            }
        }
        if (removed > 0) {
            logger.info("清理空闲会话 {} 个，当前会话数 {}", removed, sessions.size());
        }
    }

    /** 单个会话：消息队列 + 摘要 + 增量计数 */
    private static final class Session {

        private final Deque<ChatMessage> messages = new ConcurrentLinkedDeque<>();
        private volatile long lastActiveAt = System.currentTimeMillis();
        private volatile int messageCount;
        /** 会话第一条用户提问，消息裁剪后仍作为会话标题保留 */
        private volatile String preview = "";

        private void touch() {
            this.lastActiveAt = System.currentTimeMillis();
        }
    }
}
