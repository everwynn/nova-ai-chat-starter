package io.github.everwynn.nova.ai.core.llm;

import java.util.function.Consumer;

/**
 * 模型客户端 SPI。
 *
 * <p>使用方可以用自研 HttpClient、AI4J、公司网关实现本接口并注册为 Bean，
 * Starter 不会覆盖已有的 {@code LlmClient}。
 */
public interface LlmClient {

    /**
     * 流式生成。
     *
     * <p>约定：
     * <ul>
     *   <li>每段增量调用 {@code onDelta}，不要传 null 或空串；</li>
     *   <li>正常结束直接返回；失败抛出异常，由引擎转换成 {@code error} 事件；</li>
     *   <li>收到取消信号后应尽快停止回调并返回，取消导致的中断不算错误。</li>
     * </ul>
     *
     * @param request     模型与消息列表
     * @param onDelta     增量消费者
     * @param cancelSignal 取消信号
     */
    void stream(ChatRequest request, Consumer<String> onDelta, CancelSignal cancelSignal) throws Exception;
}
