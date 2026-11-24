package io.github.everwynn.nova.ai.core.llm;

/**
 * 取消信号：生成任务与上游客户端共享的只读取消状态。
 *
 * <p>实现方可以在 {@link #onCancel} 注册清理钩子（如断开 HTTP 连接）。
 * 钩子只是 best-effort，UI 收尾不依赖它。
 */
public interface CancelSignal {

    /** 永不取消的空实现 */
    CancelSignal NONE = new CancelSignal() {
        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public void onCancel(Runnable hook) {
            // 永不触发
        }
    };

    boolean isCancelled();

    /** 注册取消回调；若注册时已取消，立即执行一次 */
    void onCancel(Runnable hook);
}
