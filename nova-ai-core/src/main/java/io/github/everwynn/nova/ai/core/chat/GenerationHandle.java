package io.github.everwynn.nova.ai.core.chat;

import io.github.everwynn.nova.ai.core.llm.CancelSignal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 一次生成任务的句柄：取消状态、已生成文本、上游清理钩子、任务 Future。
 *
 * <p>收尾权通过 {@link #markFinished()} 的 CAS 在「用户停止」与「worker 自然结束」之间
 * 唯一确定，保证 {@code end} / {@code stopped} 不会重复推送，历史也不会写两遍。
 */
final class GenerationHandle implements CancelSignal {

    private static final Logger logger = LoggerFactory.getLogger(GenerationHandle.class);

    final String clientId;
    final String sessionId;
    final String question;

    private final StringBuilder text = new StringBuilder();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private final AtomicBoolean finished = new AtomicBoolean(false);
    private final List<Runnable> hooks = new CopyOnWriteArrayList<>();

    private volatile Future<?> future;
    private volatile boolean interruptOnCancel = true;

    GenerationHandle(String clientId, String sessionId, String question) {
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.question = question;
    }

    public synchronized void append(String delta) {
        text.append(delta);
    }

    public synchronized String snapshot() {
        return text.toString();
    }

    public synchronized int snapshotLength() {
        return text.length();
    }

    @Override
    public boolean isCancelled() {
        return cancelled.get();
    }

    @Override
    public void onCancel(Runnable hook) {
        if (hook == null) {
            return;
        }
        hooks.add(hook);
        // 注册时已取消：立即补执行，覆盖"先停止后拿到连接"的窗口
        if (cancelled.get()) {
            runSafely(hook);
        }
    }

    /**
     * 请求取消。
     *
     * @param interrupt 是否中断 worker 线程；hard 模式 true，soft 模式 false（让上游自然结束）
     */
    void cancel(boolean interrupt) {
        interruptOnCancel = interrupt;
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable hook : hooks) {
            runSafely(hook);
        }
        Future<?> attached = future;
        if (attached != null) {
            attached.cancel(interrupt);
        }
    }

    /** 绑定任务 Future；若已取消则立即按取消模式处理提交时序竞态 */
    void attach(Future<?> taskFuture) {
        this.future = taskFuture;
        if (cancelled.get()) {
            taskFuture.cancel(interruptOnCancel);
        }
    }

    /** 抢占收尾权：返回 true 表示由当前调用方负责写历史与推送收尾事件 */
    boolean markFinished() {
        return finished.compareAndSet(false, true);
    }

    private static void runSafely(Runnable hook) {
        try {
            hook.run();
        } catch (Exception e) {
            logger.warn("取消回调执行失败", e);
        }
    }
}
