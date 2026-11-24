package io.github.everwynn.nova.ai.core.chat;

import io.github.everwynn.nova.ai.core.event.ChatEvent;

/**
 * 向某个前端连接推送事件。
 *
 * <p>core 不依赖 Servlet：Spring MVC 的 SseEmitter 适配在 starter 模块实现。
 *
 * <p>自定义实现：注册为 Bean 并标注 {@code @Primary}，即可接管或包装内置实现
 * （例如同时推 SSE 与消息队列）。必须满足以下约束：
 * <ul>
 *   <li>{@link #send} 会被多个线程调用（worker 推 delta、HTTP 线程推 stopped、
 *       心跳线程推 ping），实现必须线程安全；</li>
 *   <li>{@link #send} 返回 false 表示没有送出，引擎不重试、不缓存；</li>
 *   <li>事件顺序由引擎保证： start → delta* → (end | stopped | error)，
 *       ping 可出现在任意位置；</li>
 *   <li>ping 是 SSE 保活专用事件，非 SSE 实现可以不处理；</li>
 *   <li>{@link #close} 幂等。</li>
 * </ul>
 */
public interface ChatGateway {

    /** 推送事件；连接不存在或已断开时返回 false，不抛异常 */
    boolean send(String clientId, ChatEvent event);

    boolean isOnline(String clientId);

    int onlineCount();

    /** 主动关闭连接，幂等 */
    void close(String clientId);
}
