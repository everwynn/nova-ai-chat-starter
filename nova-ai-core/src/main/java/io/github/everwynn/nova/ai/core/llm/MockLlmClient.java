package io.github.everwynn.nova.ai.core.llm;

import java.util.function.Consumer;

/**
 * 本地 Mock 模型客户端：按固定间隔逐字输出，用于离线演示与测试。
 *
 * <p>不访问网络，不需要 API Key；收到取消信号立即停止。
 */
public class MockLlmClient implements LlmClient {

    private static final String DEFAULT_TEXT =
            "Nova AI Chat Starter 只处理 SSE 对话的生命周期：把浏览器长连接、会话历史、停止语义、"
                    + "心跳和生成线程池管好，模型调用留给 LlmClient 接口。你仍然可以使用现有的 DeepSeek、"
                    + "通义兼容服务或公司网关，不需要升级 JDK，也不需要引入完整的 AI 框架。"
                    + "点击停止后，前端会立即结束等待并标注已停止，已生成的部分写入历史，刷新页面依然可以看到。"
                    + "所有开关默认关闭，只有显式配置 nova.ai.enabled=true 之后才会装配 Bean，避免影响存量项目。";

    private final String text;
    private final long intervalMillis;

    public MockLlmClient(long intervalMillis) {
        this(DEFAULT_TEXT, intervalMillis);
    }

    public MockLlmClient(String text, long intervalMillis) {
        this.text = text == null ? "" : text;
        this.intervalMillis = Math.max(0L, intervalMillis);
    }

    @Override
    public void stream(ChatRequest request, Consumer<String> onDelta, CancelSignal cancelSignal) throws Exception {
        for (int i = 0; i < text.length(); i++) {
            if (cancelSignal.isCancelled()) {
                return;
            }
            onDelta.accept(String.valueOf(text.charAt(i)));
            if (intervalMillis > 0L) {
                Thread.sleep(intervalMillis);
            }
        }
    }
}
