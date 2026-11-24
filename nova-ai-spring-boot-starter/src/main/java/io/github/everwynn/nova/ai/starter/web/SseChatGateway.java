package io.github.everwynn.nova.ai.starter.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.everwynn.nova.ai.core.chat.ChatGateway;
import io.github.everwynn.nova.ai.core.event.ChatEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Spring MVC {@link SseEmitter} 的连接网关。
 *
 * <p>职责：连接注册/替换、事件推送、心跳、断线清理、在线数。
 * 所有事件（含 connect / ping）都走 SSE 的 {@code message} 事件，
 * 前端只监听 {@code onmessage}，再按 {@code data.type} 分发。
 */
public class SseChatGateway implements ChatGateway, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SseChatGateway.class);

    /** 心跳分片线程上限：慢客户端最多占住一个分片，不会拖住全部连接 */
    private static final int MAX_PING_WORKERS = 4;

    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final long timeoutMillis;
    private final long heartbeatMillis;
    private final int pingWorkers;
    private final ScheduledExecutorService heartbeatScheduler;
    private final ThreadPoolExecutor pingExecutor;

    public SseChatGateway(ObjectMapper objectMapper, long timeoutMillis, long heartbeatMillis) {
        this.objectMapper = objectMapper;
        this.timeoutMillis = Math.max(0L, timeoutMillis);
        this.heartbeatMillis = Math.max(1000L, heartbeatMillis);
        this.pingWorkers = Math.max(1, Math.min(MAX_PING_WORKERS,
                Runtime.getRuntime().availableProcessors()));
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(
                daemonFactory("nova-sse-heartbeat"));
        // 有界队列 + DiscardPolicy：上一轮心跳未完成时丢弃本轮分片，心跳宁可少发也不堆积
        this.pingExecutor = new ThreadPoolExecutor(
                pingWorkers, pingWorkers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, pingWorkers * 2)),
                daemonFactory("nova-sse-ping-"),
                new ThreadPoolExecutor.DiscardPolicy());
        this.heartbeatScheduler.scheduleAtFixedRate(this::dispatchPing,
                this.heartbeatMillis, this.heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * 建立连接。同一 clientId 重复连接会先关闭旧连接，避免多标签页重复订阅。
     */
    public SseEmitter connect(String clientId) {
        SseEmitter old = emitters.remove(clientId);
        if (old != null) {
            old.complete();
            logger.info("替换旧SSE连接, clientId={}", clientId);
        }

        SseEmitter emitter = new SseEmitter(timeoutMillis);
        emitters.put(clientId, emitter);

        // 双参 remove：旧连接的回调晚于重连时不会误删新连接
        emitter.onCompletion(() -> emitters.remove(clientId, emitter));
        emitter.onTimeout(() -> {
            emitters.remove(clientId, emitter);
            logger.warn("SSE连接超时, clientId={}", clientId);
        });
        emitter.onError(ex -> {
            emitters.remove(clientId, emitter);
            logger.warn("SSE连接异常, clientId={}, msg={}", clientId, ex.getMessage());
        });

        sendTo(emitter, ChatEvent.connect());
        logger.info("SSE连接建立, clientId={}, online={}", clientId, emitters.size());
        return emitter;
    }

    @Override
    public boolean send(String clientId, ChatEvent event) {
        SseEmitter emitter = emitters.get(clientId);
        if (emitter == null) {
            return false;
        }
        if (sendTo(emitter, event)) {
            return true;
        }
        emitters.remove(clientId, emitter);
        return false;
    }

    @Override
    public boolean isOnline(String clientId) {
        return emitters.containsKey(clientId);
    }

    @Override
    public int onlineCount() {
        return emitters.size();
    }

    @Override
    public void close(String clientId) {
        SseEmitter emitter = emitters.remove(clientId);
        if (emitter != null) {
            emitter.complete();
            logger.info("手动关闭SSE连接, clientId={}", clientId);
        }
    }

    @Override
    public void close() {
        heartbeatScheduler.shutdownNow();
        pingExecutor.shutdownNow();
        emitters.forEach((clientId, emitter) -> {
            try {
                emitter.complete();
            } catch (Exception e) {
                logger.debug("关闭连接失败, clientId={}", clientId, e);
            }
        });
        emitters.clear();
    }

    /**
     * 心跳分片：调度线程只做快照与提交，实际发送在固定线程池里并行执行。
     * 某个客户端写阻塞时最多占住一个分片线程，其他连接的心跳不受影响。
     */
    private void dispatchPing() {
        if (emitters.isEmpty()) {
            return;
        }
        List<Map.Entry<String, SseEmitter>> snapshot = new ArrayList<>(emitters.entrySet());
        int shards = Math.min(pingWorkers, snapshot.size());
        for (int i = 0; i < shards; i++) {
            int from = i * snapshot.size() / shards;
            int to = (i + 1) * snapshot.size() / shards;
            List<Map.Entry<String, SseEmitter>> slice = snapshot.subList(from, to);
            pingExecutor.execute(() -> pingSlice(slice));
        }
    }

    private void pingSlice(List<Map.Entry<String, SseEmitter>> slice) {
        for (Map.Entry<String, SseEmitter> entry : slice) {
            if (!sendTo(entry.getValue(), ChatEvent.ping())) {
                emitters.remove(entry.getKey(), entry.getValue());
            }
        }
    }

    private boolean sendTo(SseEmitter emitter, ChatEvent event) {
        try {
            String json = objectMapper.writeValueAsString(event);
            emitter.send(SseEmitter.event().name("message").data(json, MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            // 客户端已断开等场景：只记录调试日志，由调用方决定是否清理
            logger.debug("SSE推送失败, event={}, msg={}", event.getType(), e.getMessage());
            try {
                emitter.completeWithError(e);
            } catch (Exception ignored) {
                // 已经关闭
            }
            return false;
        }
    }

    private static ThreadFactory daemonFactory(String prefix) {
        return runnable -> {
            Thread thread = new Thread(runnable, prefix);
            thread.setDaemon(true);
            return thread;
        };
    }
}
