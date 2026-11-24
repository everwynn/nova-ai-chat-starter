# nova-ai-chat-spring-boot-starter

> 状态：0.1.0 已实现，未发布到 Maven Central。

## 职责

- 为 Spring Boot 2.7 提供自动配置（`@AutoConfiguration` + `AutoConfiguration.imports`）。
- 注册 `nova.ai.*` 配置项（`NovaAiProperties`）。
- 按需注册 `/api/ai/chat/*` 端点（默认关闭）。
- 把 core 的 SPI 与 Spring Bean 连接起来，用户已有 Bean 不覆盖（`@ConditionalOnMissingBean`）。

## 非职责

- 不实现 SSE 生命周期与调度（在 `nova-ai-core`）。
- 不实现模型调用（通过 `LlmClient` SPI）。
- 不做安全认证；鉴权交给使用方的 Spring Security 或网关。
- 不引入 WebFlux。

## 已实现类

```text
io.github.everwynn.nova.ai.starter
├── NovaAiAutoConfiguration
├── NovaAiProperties
└── web
    ├── NovaAiChatController
    ├── SseChatGateway（SseEmitter 适配 + 心跳 + 断线清理）
    ├── ChatSendRequest
    └── Result
```

## 两级开关

| 配置 | 默认值 | 效果 |
|---|---|---|
| `nova.ai.enabled` | `false` | 不装配任何核心 Bean，不创建线程池 |
| `nova.ai.endpoint.enabled` | `false` | 不注册 HTTP 端点 |

## 端点

```text
GET    /api/ai/chat/connect/{clientId}
POST   /api/ai/chat/send
POST   /api/ai/chat/stop/{sessionId}
POST   /api/ai/chat/session/{sessionId}/clear
GET    /api/ai/chat/history/{sessionId}
GET    /api/ai/chat/sessions
DELETE /api/ai/chat/session/{sessionId}
POST   /api/ai/chat/close/{clientId}
GET    /api/ai/chat/online
```

## 验收

`mvn test` 已覆盖：

- 默认不装配 Bean、不注册端点；
- `nova.ai.enabled=true` 装配核心 Bean，但端点仍关闭；
- 再设 `nova.ai.endpoint.enabled=true` 才注册控制器。

示例见 [examples/spring-boot-demo](../examples/spring-boot-demo)。
