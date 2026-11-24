package io.github.everwynn.nova.ai.core.chat;

import io.github.everwynn.nova.ai.core.config.NovaAiSettings;
import io.github.everwynn.nova.ai.core.event.ChatEvent;
import io.github.everwynn.nova.ai.core.llm.CancelSignal;
import io.github.everwynn.nova.ai.core.llm.CancelSignals;
import io.github.everwynn.nova.ai.core.llm.ChatRequest;
import io.github.everwynn.nova.ai.core.llm.LlmClient;
import io.github.everwynn.nova.ai.core.session.ChatMessage;
import io.github.everwynn.nova.ai.core.session.ChatSessionStore;
import io.github.everwynn.nova.ai.core.session.SessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 对话生成引擎：同一会话单任务、有界队列、停止收尾、历史写入。
 *
 * <p>停止语义（与参考实现的关键差异）：
 * {@link #stop(String)} 在发出取消信号后，<b>立即</b>抢占收尾权并完成
 * 「取快照 → 写历史 → 推 stopped → 释放 running」。worker 线程是否及时读到取消、
 * 上游连接能否立即断开，都不再决定前端等待时长。
 */
public class ChatEngine implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(ChatEngine.class);

    private static final int SHUTDOWN_WAIT_SECONDS = 5;

    private final LlmClient llmClient;
    private final ChatSessionStore sessionStore;
    private final ChatGateway gateway;
    private final NovaAiSettings settings;
    private final ThreadPoolExecutor executor;
    private final Map<String, GenerationHandle> running = new ConcurrentHashMap<>();
    private final AtomicInteger threadSeq = new AtomicInteger(0);

    public ChatEngine(LlmClient llmClient,
                      ChatSessionStore sessionStore,
                      ChatGateway gateway,
                      NovaAiSettings settings) {
        this.llmClient = llmClient;
        this.sessionStore = sessionStore;
        this.gateway = gateway;
        this.settings = settings;

        int workers = Math.max(1, settings.getWorkerThreads());
        this.executor = new ThreadPoolExecutor(
                workers, workers,
                0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, settings.getQueueCapacity())),
                r -> {
                    Thread t = new Thread(r, "nova-chat-worker-" + threadSeq.getAndIncrement());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    /**
     * 发送一条消息并异步生成回答。
     *
     * @throws IllegalStateException    当前会话已有生成中的回答
     * @throws RejectedExecutionException 队列已满或线程池已关闭
     */
    public void send(String clientId, String sessionId, String question) {
        final GenerationHandle handle = new GenerationHandle(clientId, sessionId, question);
        if (running.putIfAbsent(sessionId, handle) != null) {
            throw new IllegalStateException("当前会话已有回答生成中，请等待完成");
        }
        try {
            Future<?> future = executor.submit(() -> runGeneration(handle));
            // 任务可能已执行到取消分支才拿到 future，句柄内部会补 cancel
            handle.attach(future);
        } catch (RejectedExecutionException e) {
            running.remove(sessionId, handle);
            logger.warn("聊天任务被拒绝, sessionId={}, 队列已满或线程池已关闭", sessionId);
            throw e;
        }
    }

    /**
     * 停止指定会话的生成。
     *
     * <p>返回时前端所需的事件已经发出，历史已经写入，running 已经释放；
     * 不等待 worker 线程或上游连接收尾。
     */
    public StopResult stop(String sessionId) {
        GenerationHandle handle = running.get(sessionId);
        if (handle == null) {
            return StopResult.notRunning();
        }
        // 1) 先置取消：后续增量一律丢弃，hard 模式同时触发上游断开钩子
        handle.cancel(settings.isHardStop());

        // 2) 立即抢占收尾权；若 worker 抢先收尾，它会按取消状态推送 stopped
        if (!handle.markFinished()) {
            return StopResult.stopped(handle.snapshotLength());
        }

        // 3) 同步完成收尾：快照 → 历史 → 释放 running → 推 stopped
        String partial = handle.snapshot();
        if (!partial.isEmpty()) {
            sessionStore.append(sessionId, ChatMessage.user(handle.question));
            sessionStore.append(sessionId, ChatMessage.assistant(partial));
        }
        running.remove(sessionId, handle);
        gateway.send(handle.clientId, ChatEvent.stopped(partial));
        logger.info("生成已停止, sessionId={}, mode={}, partialLength={}",
                sessionId, settings.isHardStop() ? "hard" : "soft", partial.length());
        return StopResult.stopped(partial.length());
    }

    public List<ChatMessage> history(String sessionId) {
        return sessionStore.history(sessionId);
    }

    public void clear(String sessionId) {
        sessionStore.clear(sessionId);
    }

    public List<SessionSummary> sessions() {
        return sessionStore.list();
    }

    public int onlineCount() {
        return gateway.onlineCount();
    }

    public boolean isRunning(String sessionId) {
        return running.containsKey(sessionId);
    }

    private void runGeneration(GenerationHandle handle) {
        try {
            // stop 可能抢在任务开始前完成收尾：此时不再推 start，也不请求上游
            if (handle.isCancelled()) {
                return;
            }
            gateway.send(handle.clientId, ChatEvent.start());
            llmClient.stream(
                    new ChatRequest(settings.getModel(), buildMessages(handle.sessionId, handle.question)),
                    delta -> {
                        // 取消后丢弃迟到增量；前端已收到 stopped
                        if (handle.isCancelled() || delta == null || delta.isEmpty()) {
                            return;
                        }
                        handle.append(delta);
                        gateway.send(handle.clientId, ChatEvent.delta(delta));
                    },
                    signalFor(handle));
            finishNormally(handle);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            if (!handle.isCancelled()) {
                finishWithError(handle, e);
            }
        } catch (Exception e) {
            if (handle.isCancelled()) {
                // 取消打断阻塞读取产生的异常属于预期，收尾已由 stop 完成
                logger.info("生成被取消, sessionId={}, reason={}", handle.sessionId, e.getMessage());
            } else {
                finishWithError(handle, e);
            }
        } finally {
            running.remove(handle.sessionId, handle);
        }
    }

    private void finishNormally(GenerationHandle handle) {
        if (!handle.markFinished()) {
            // stop() 已收尾
            return;
        }
        String answer = handle.snapshot();
        boolean cancelled = handle.isCancelled();
        if (!answer.isEmpty()) {
            sessionStore.append(handle.sessionId, ChatMessage.user(handle.question));
            sessionStore.append(handle.sessionId, ChatMessage.assistant(answer));
        }
        // 极端竞态：取消发生在本方法检查之后，仍按 stopped 收尾，避免 end/stopped 语义错乱
        if (cancelled) {
            gateway.send(handle.clientId, ChatEvent.stopped(answer));
            logger.info("生成已停止(worker收尾), sessionId={}, partialLength={}",
                    handle.sessionId, answer.length());
            return;
        }
        gateway.send(handle.clientId, ChatEvent.end(answer));
        logger.info("生成完成, sessionId={}, answerLength={}", handle.sessionId, answer.length());
    }

    private void finishWithError(GenerationHandle handle, Exception error) {
        if (handle.isCancelled() || !handle.markFinished()) {
            return;
        }
        String message = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
        logger.error("聊天生成失败, sessionId={}", handle.sessionId, error);
        // error 不落半截回答，避免污染下一轮上下文
        gateway.send(handle.clientId, ChatEvent.error(message));
    }

    /** 组装消息列表：system 提示词（可选）+ 会话历史 + 当前问题 */
    private List<ChatMessage> buildMessages(String sessionId, String question) {
        List<ChatMessage> messages = new ArrayList<>();
        String systemPrompt = settings.getSystemPrompt();
        if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
            messages.add(ChatMessage.system(systemPrompt.trim()));
        }
        messages.addAll(sessionStore.history(sessionId));
        messages.add(ChatMessage.user(question));
        return messages;
    }

    /** hard 模式让上游感知取消（断开）；soft 模式上游不感知，仅丢弃迟到增量 */
    private CancelSignal signalFor(GenerationHandle handle) {
        return settings.isHardStop() ? handle : CancelSignals.withoutHooks(handle);
    }

    @Override
    public void close() {
        // 先收尾运行中的任务：把已生成的部分落库，避免应用重启后历史只剩提问
        for (GenerationHandle handle : running.values()) {
            handle.cancel(true);
            if (handle.markFinished()) {
                String partial = handle.snapshot();
                if (!partial.isEmpty()) {
                    sessionStore.append(handle.sessionId, ChatMessage.user(handle.question));
                    sessionStore.append(handle.sessionId, ChatMessage.assistant(partial));
                }
                logger.info("关闭时收尾未完成任务, sessionId={}, partialLength={}",
                        handle.sessionId, partial.length());
            }
        }
        running.clear();

        executor.shutdown();
        try {
            if (!executor.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                logger.warn("nova-chat 线程池 {}s 内未结束，强制关闭", SHUTDOWN_WAIT_SECONDS);
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
