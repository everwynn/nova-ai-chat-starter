# Nova AI Chat Starter

JDK8 / Spring Boot 2.3–2.7 的 SSE 对话生命周期 Starter。不升级 JDK，不引入完整 AI 框架，把连接、会话、停止、心跳和有界队列管好。

> **Status: 0.1.0 已发布到 Maven Central；0.1.1 已完成、待发布。**
> `io.github.everwynn:nova-ai-chat-spring-boot-starter:0.1.0` 连同 parent 与 core 可直接从中央仓库拉取；
> 0.1.1 起支持 Spring Boot 2.3.x–2.7.x（新增 `spring.factories` 注册），0.1.0 仅支持 2.7.x。
> 17 个自动化测试 + 1 个 2.3.7 冒烟测试通过；SSE 流式、停止生成、刷新回显已在浏览器实测。API 仍可能调整，暂不建议直接用于生产。
> 如果这个方向对你有用，请通过 Issue 或 Discussions 告诉我你的场景。

**一句话定位：**

> Bring your own model client; we handle the SSE chat lifecycle.

---

## 目录

- [Why this exists](#why-this-exists)
- [Why not just use AI4J or Solon AI?](#why-not-just-use-ai4j-or-solon-ai)
- [What it does](#what-it-does)
- [What it will NOT do](#what-it-will-not-do)
- [Who it's for](#who-its-for)
- [Compatibility](#compatibility)
- [Design notes](#design-notes)
- [Repository status](#repository-status)
- [Roadmap](#roadmap)
- [Quick start](#quick-start)
- [Documentation](#documentation)
- [Feedback wanted](#feedback-wanted)
- [License](#license)

---

## Why this exists

很多 JDK8 / Spring Boot 2.7 项目不是不想升级，而是升不动：`javax` 迁移、依赖树、停机窗口、合规审批，每一项都要时间。

与此同时，AI 接入已经有两类成熟方案：

- **完整框架**：Spring AI、LangChain4j、Solon AI。
- **JDK8 SDK**：AI4J。

但它们解决的主要是「怎么调模型」「怎么做 RAG/Agent」。真正让对话上线的，是另一层问题：

- `EventSource` 不能自定义 Header，鉴权怎么做？
- 连接断了怎么清理？心跳怎么发？
- 用户点「停止生成」，是断开上游，还是只停止推送？
- 会话历史什么时候写？半截回答要不要落库？
- 生成线程池多大？队列满了怎么办？
- 多实例部署，会话放内存还是 Redis？

**这一层，通常要靠复制博客代码，然后每个人踩一遍坑。**

这个项目只做这一层。

---

## Why not just use AI4J or Solon AI?

| 你的需求 | 建议 |
|---|---|
| 多模型接入、RAG、Tool Call、Agent | 直接用 AI4J 或 Solon AI |
| 新项目，可以升级 JDK17 / Boot 3 | 直接用 Spring AI 或 LangChain4j |
| 已有模型调用，只想把 SSE 对话跑稳 | 用这个 Starter |
| 不想引入完整框架，想保持低锁定 | 用这个 Starter |
| 需要开箱即用的完整 AI 平台 | 不适合这个项目 |

这个 Starter 不替代 AI4J / Solon AI。它甚至可以和它们共存：

- 你可以用 AI4J 作为模型客户端，把它包装成 `LlmClient`。
- 你也可以用公司现有网关，只把 SSE 生命周期交给这个 Starter。
- 内置的 OpenAI 兼容客户端只是「默认实现」，不是唯一选择。

---

## What it does

- SSE 连接管理：注册、心跳、断线清理、在线数监控（`SseChatGateway`）。
- 会话历史：按 sessionId 存取，保留最近 N 轮，内存实现带 TTL 清扫与上限淘汰，支持自定义 SPI。
- 停止生成：收到停止请求后由 HTTP 线程立即收尾——写已生成部分、推 `stopped`、释放运行位；`hard` 额外 best-effort 断开上游连接，`soft` 不打断上游。
- 生成调度：固定线程池 + 有界队列，队列满返回「服务繁忙」。
- 事件协议：`start` / `delta` / `end` / `stopped` / `error`，外加 `connect` / `ping`；统一 `message` 事件 + `type` 字段。
- 模型客户端：`LlmClient` SPI，内置 OpenAI 兼容实现与 Mock 实现（`nova.ai.mock=true` 可离线演示）。
- 自动配置：Boot 2.7 原生，`AutoConfiguration.imports` + `@AutoConfiguration`。
- 可选端点：9 个 HTTP 端点，`nova.ai.endpoint.enabled=true` 才暴露；默认不装配、不覆盖用户已有 Bean。

## What it will NOT do

- 不做 RAG / Agent / Tool Calling。
- 不做模型路由 / AI 网关。
- 不做前端 UI。
- 不做托管服务。
- 不承诺 SLA，不承诺长期维护（验证阶段）。

---

## Who it's for

- 仍在 JDK8 / Spring Boot 2.7 的存量 Spring MVC 项目。
- 已经有模型调用方式（自研 HttpClient、AI4J、公司网关），只缺 SSE 治理。
- 不想引入完整 AI 框架，想保持低锁定。
- 需要会话、停止、心跳、断线、队列这些生产细节。

## Who it's NOT for

- 新项目，已经升级 JDK17 / Boot 3。
- 需要 RAG / Agent / Tool Calling。
- 想要开箱即用的完整 AI 平台。
- 只需要一次性非流式调用。

---

## Compatibility

| 项目 | 支持范围 |
|---|---|
| JDK | 8 |
| Spring Boot | 2.3.x – 2.7.x（0.1.1 起）；0.1.0 仅 2.7.x |
| Servlet | `javax.servlet` 3.1+ |
| Web 栈 | Spring MVC（不要求 WebFlux） |
| 模型服务 | OpenAI 兼容（OpenAI / DeepSeek / 通义兼容 / vLLM / Ollama） |
| 构建 | Maven |

不支持的组合：

- **Spring Boot 2.3.x – 2.6.x**：需要 0.1.1 及以上（走 `spring.factories`）。0.1.0 在这些版本下**启动正常、无任何报错，但 `/api/ai/chat/*` 全部 404**。
- **Spring Boot 2.2 及以下**：未适配。
- **Spring Boot 3.x**：`javax.servlet` → `jakarta.servlet`，需另发适配版。
- **WebFlux**、**JDK 7 及以下**。

---

## Design notes

- **自动配置注册**：Boot 2.7 原生使用 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` + `@AutoConfiguration`；旧的 `spring.factories` 仍兼容，但本项目不作为主路径。
- **命名空间**：`javax.servlet`，不是 `jakarta.servlet`。
- **命名**：`nova-ai-chat-spring-boot-starter` 遵循第三方 Starter 的 `*-spring-boot-starter` 约定；`spring-boot-starter-*` 前缀留给官方 Starter。
- **SSE 实现**：Spring MVC `SseEmitter`，不引入 WebFlux。
- **上游调用**：`HttpURLConnection`，零第三方 SDK。
- **默认关闭**：`nova.ai.enabled=false` 不装配核心 Bean；`nova.ai.endpoint.enabled=false` 不暴露端点，避免和现有安全体系冲突。
- **错误统一为事件**：HTTP 层尽量不抛 500，用 `error` 事件把上游错误透给前端。
- **历史写入策略**：`end` 写完整问答；用户主动 `stopped` 写已生成部分（历史只存 `role` + `content`，不带状态标记）；`error` 不落半截回答。
- **停止语义（0.1.0 起）**：停止请求在 HTTP 线程内**同步完成收尾**——快照已生成内容、写历史、推 `stopped`、释放运行位，不等待 worker 或上游连接。JDK8 下 `HttpURLConnection.disconnect()` 是 best-effort，UI 收尾不依赖它。
- **两种停止模式**：`hard` 注册断开钩子尝试中断上游（省 token），`soft` 完全不打断上游、worker 侧丢弃迟到增量。两种模式的前端表现一致。
- **唯一收尾权**：`GenerationHandle.markFinished()` 用 CAS 保证 stop 与 worker 之间只有一个执行写历史并推送收尾事件，`end` / `stopped` 互斥、不重复写历史。
- **会话存储 SPI**：默认内存，实现 `ChatSessionStore` 即可替换为文件 / Redis / DB。
- **事件出口 SPI**：内置 `SseChatGateway`；实现 `ChatGateway` 并加 `@Primary` 可双写 MQ 或换成 WebSocket。
- **优雅关闭**：`close()` 会取消运行中任务并把已生成部分落库，避免重启后历史只剩提问。

**模块关系：**

```text
your Spring MVC app
        |
        v
nova-ai-chat-spring-boot-starter   (auto-config, endpoints, properties)
        |
        v
nova-ai-core                       (SSE lifecycle, session, scheduler, SPI)
        |
        +--> LlmClient (SPI) --> OpenAI-compatible / AI4J / company gateway
        |
        +--> ChatSessionStore (SPI) --> memory / file / Redis / DB
```

---

## Repository status

| 内容 | 状态 |
|---|---|
| README / 文档 | 已写；README 含完整接入步骤，`docs/` 扩展文档未纳入版本控制 |
| `nova-ai-core` | 已实现：模型、SPI、SSE 抽象、会话存储、调度、停止收尾 |
| `nova-ai-spring-boot-starter` | 已实现：properties、自动配置、9 个端点；发布 artifact 名为 `nova-ai-chat-spring-boot-starter` |
| examples | `spring-boot-demo` 已实现并可运行；`plain-html`、`vue-chat` 计划中 |
| 自动化测试 | 17 个通过（core 12 + starter 5）；另有 2.3.7 冒烟测试 1 个 |
| Boot 兼容验证 | 2.3.7 与 2.7.18 均跑通（示例在 `examples/`） |
| 浏览器实测 | 流式 / 停止（22ms 收尾）/ 刷新回显，通过 |
| LICENSE / NOTICE | 已添加（Apache-2.0） |
| Maven 发布 | 已发布 0.1.0 到 Maven Central（parent + core + starter） |
| CI | 未配置 |

实现由参考工程 `nova-chat-single`（53 个测试、9 个端点、零第三方 SDK）抽取而来，在独立仓库中重写并补充了停止语义修复。

---

## Roadmap

- [ ] 需求验证（进行中，D10–D14 访谈）
- [x] Demo 分镜脚本（验证文档）
- [ ] 录制 48 秒 demo + 12 秒 GIF + 3 张截图
- [x] 抽取 `nova-ai-core`
- [x] 写 Starter 自动配置与 properties
- [x] `spring-boot-demo` 示例
- [ ] 其余示例：plain-html、vue-chat
- [x] Maven Central 发布（0.1.0，2026-11-26）
- [x] Boot 2.3–2.6 兼容（`spring.factories`，0.1.1）
- [ ] 兼容性矩阵
- [ ] 生产检查清单
- [ ] Boot 3 迁移指南

---

## Quick start

`0.1.0` 已发布到 Maven Central，业务项目直接引依赖即可，不需要先本地 `install`。

> **前置条件：JDK 8 + Spring Boot 2.3.x–2.7.x（0.1.1 起；0.1.0 仅支持 2.7.x）。** 0.1.0 在 2.6 及以下不会加载自动配置（`AutoConfiguration.imports` 机制 2.7 才引入），端点会静默 404；Spring Boot 3.x 因 `jakarta.servlet` 不兼容。

### 1. 添加依赖

Maven：

```xml
<dependency>
  <groupId>io.github.everwynn</groupId>
  <artifactId>nova-ai-chat-spring-boot-starter</artifactId>
  <version>0.1.0</version>
</dependency>
```

Gradle：

```groovy
implementation 'io.github.everwynn:nova-ai-chat-spring-boot-starter:0.1.0'
```

`nova-ai-core` 作为传递依赖自动引入；父 POM `nova-ai-chat-parent` 由 Maven 从中央仓库解析，业务项目**不需要**继承它。

### 2. 配置

两个开关默认都是 `false`，必须同时打开：

```yaml
spring:
  mvc:
    async:
      request-timeout: -1        # 防止容器层掐断 SSE 长连接

nova:
  ai:
    enabled: true                # 装配核心 Bean（默认 false）
    endpoint:
      enabled: true              # 暴露 /api/ai/chat/*（默认 false）
    base-url: https://api.deepseek.com
    api-key: ${LLM_API_KEY}      # 用环境变量注入，不要提交到仓库
    model: deepseek-chat
    stop-mode: hard              # hard=尽量断开上游；soft=不打断上游
    worker-threads: 4
    queue-capacity: 200
    heartbeat-millis: 15000
```

不接模型、只想先跑通：加 `nova.ai.mock=true`，逐字输出、不访问网络。

### 3. 启动自检

```bash
curl http://localhost:8080/api/ai/chat/online
# {"code":0,"msg":"success","data":0}
```

### 4. 前端最小接入

```javascript
// 1) 先建立 SSE 连接
const es = new EventSource('/api/ai/chat/connect/c-1');
let closed = false;
es.onmessage = (e) => {
  const evt = JSON.parse(e.data);
  if (evt.type === 'delta' && !closed) append(evt.content);
  if (evt.type === 'end' || evt.type === 'stopped') { closed = true; es.close(); }
  if (evt.type === 'error') { closed = true; es.close(); }
};

// 2) 再发送消息
fetch('/api/ai/chat/send', {
  method: 'POST',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ clientId: 'c-1', sessionId: 's-1', message: '你好' })
});
```

`stopped` / `end` 之后仍可能滑入一个迟到的 `delta`，前端必须用类似的 `closed` 标志忽略它。

### 端点

`nova.ai.endpoint.enabled=true` 时注册，前缀 `/api/ai/chat`：

| 方法 | 路径 | 说明 |
|---|---|---|
| `GET` | `/connect/{clientId}` | 建立 SSE 连接，立即收到 `connect` 事件 |
| `POST` | `/send` | 发送消息并触发流式生成 |
| `POST` | `/stop/{sessionId}` | 停止该会话当前生成 |
| `POST` | `/session/{sessionId}/clear` | 清空会话历史 |
| `GET` | `/history/{sessionId}` | 查询会话历史 |
| `GET` | `/sessions` | 列出会话 |
| `DELETE` | `/session/{sessionId}` | 删除会话 |
| `POST` | `/close/{clientId}` | 主动关闭 SSE 连接 |
| `GET` | `/online` | 在线连接数 |

约束：`clientId` / `sessionId` 匹配 `[A-Za-z0-9_-]{1,64}`；`message` 非空且不超过 4000 字符；`sessionId` 为空时默认取 `clientId`。响应统一为 `Result`，`code=0` 成功，`code=1` 表示业务失败（重复发送、队列已满、参数不合法）。

### 接入前必看

| 事项 | 说明 |
|---|---|
| Spring Boot 2.3–2.6 | 用 **0.1.1+**（走 `spring.factories`）；0.1.0 在这些版本下启动正常但端点全部 404（无报错） |
| Spring Boot 3.x | **不支持**：`javax.servlet` → `jakarta.servlet`，需另发适配版 |
| Web 栈 | 仅 Spring MVC；WebFlux 项目不适用 |
| 鉴权 | 端点不带鉴权；`EventSource` 不能自定义 Header，需用 Cookie 或网关兜住 |
| 多实例 | 默认内存会话，不跨实例共享；实现 `ChatSessionStore` 换 Redis / DB |
| 反向代理 | Nginx 需 `proxy_buffering off`，`proxy_read_timeout` 大于心跳间隔 |
| 线程池 | 队列满时 `send` 返回「服务繁忙」，按并发调 `worker-threads` / `queue-capacity` |
| 国内镜像 | 镜像同步 Central 有延迟，拉不到时可临时指定 `https://repo1.maven.org/maven2` |

### 本地运行示例

```bash
mvn test
mvn -DskipTests install
cd examples/spring-boot-demo
mvn spring-boot:run
```

打开 <http://localhost:8080/chat.html>（默认 mock 模式，不需要 API Key）。

完整配置项、事件协议、Redis 存储与自定义事件出口见 `docs/quickstart.md`（本地扩展文档，当前未纳入版本控制）。

---

## Documentation

- [nova-ai-core/README.md](nova-ai-core/README.md)：核心模块结构与设计。
- [nova-ai-spring-boot-starter/README.md](nova-ai-spring-boot-starter/README.md)：Starter 配置与端点。
- [examples/README.md](examples/README.md)：示例运行方式。
- `docs/quickstart.md`：完整配置参考、事件协议、Redis 存储与自定义事件出口（本地扩展文档，当前未纳入版本控制）。
- `docs/release-process.md`：GitHub 建仓、打 tag、Maven Central 发布全流程（同上）。
- Configuration、Compatibility、Production Checklist、Boot 3 Migration：计划中。

---

## Feedback wanted

如果你也在 JDK8 / Spring Boot 2.7 上做 AI 接入，想请教：

1. 你们现在 JDK / Spring Boot 版本？为什么没有升级？
2. 现在怎么接大模型：自研 HTTP、AI4J、Solon AI、公司网关，还是别的？
3. 最大的卡点是什么：升级、SSE、会话、鉴权、运维，还是成本？
4. 如果有一个只做 SSE 生命周期的 Starter，你会引入吗？需要什么条件？

---

## License

Apache-2.0。Copyright 2026 Everwynn。

- [LICENSE](LICENSE)：Apache-2.0 官方协议文本。
- [NOTICE](NOTICE)：版权声明。
