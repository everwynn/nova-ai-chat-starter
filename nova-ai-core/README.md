# nova-ai-core

> 状态：0.1.0 已实现。与 Spring 无关，可在纯 JUnit 环境运行。

## 职责

- SSE 生命周期抽象：`ChatGateway`（连接推送、在线数、关闭）。
- 会话生命周期：历史、过期、上限、`ChatSessionStore` SPI。
- 生成调度：有界线程池、队列满拒绝、同一会话单任务。
- 停止语义：`hard` / `soft`，`stop()` 在调用线程内立即收尾。
- 事件协议：`connect` / `ping` / `start` / `delta` / `end` / `stopped` / `error`。
- SPI：`LlmClient`、`ChatSessionStore`，内置 OpenAI 兼容与 Mock 实现。

## 非职责

- 不提供 HTTP 端点（由 starter 模块负责）。
- 不依赖 Spring / Servlet / WebFlux。
- 不做 RAG / Agent / Tool Calling，不做模型路由。

## 包结构

```text
io.github.everwynn.nova.ai.core
├── chat      ChatEngine、GenerationHandle、StopResult、ChatGateway
├── event     ChatEvent
├── llm       LlmClient、CancelSignal、ChatRequest、OpenAI 兼容、Mock
├── session   ChatMessage、ChatRole、ChatSessionStore、内存实现、SessionSummary
└── config    NovaAiSettings
```

## 关键设计

- **停止立即收尾**：`ChatEngine.stop()` 先置取消，再用 CAS 抢收尾权，随后同步完成
  「快照 → 写历史 → 释放 running → 推 `stopped`」。worker 线程与上游连接是否及时结束，
  不再决定前端等待时长。
- **唯一收尾**：`GenerationHandle.markFinished()` 保证 `end` / `stopped` 只推送一次，历史不重复写。
- **soft 模式**：`CancelSignals.withoutHooks()` 让上游自然结束，增量消费端丢弃迟到内容。
- **取消只是 best-effort**：JDK8 的 `HttpURLConnection.disconnect()` 不保证立即打断阻塞读，
  因此它只用于尽快中止上游计费。

## 验收

`mvn test` 覆盖：

- 正常结束写历史并推送 `end`；
- 流式中停止在 500ms 内返回、推送 `stopped`、写入已生成部分、可重复停止；
- 上游异常推送 `error` 且不写历史。
