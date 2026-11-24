package io.github.everwynn.nova.ai.core.llm;

/**
 * 取消信号的包装工具。
 */
public final class CancelSignals {

    private CancelSignals() {
    }

    /**
     * 只读包装：保留取消状态查询，忽略钩子注册。
     *
     * <p>soft 停止模式使用：不向上游注册断开钩子，让上游自然结束，
     * 但增量消费端仍能通过 {@link CancelSignal#isCancelled()} 丢弃迟到内容。
     */
    public static CancelSignal withoutHooks(final CancelSignal delegate) {
        return new CancelSignal() {
            @Override
            public boolean isCancelled() {
                return delegate.isCancelled();
            }

            @Override
            public void onCancel(Runnable hook) {
                // soft 模式不打断上游
            }
        };
    }
}
