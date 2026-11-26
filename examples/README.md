# Examples

> 状态：`spring-boot-demo`（2.7.18）与 `spring-boot-demo-237`（2.3.7）已实现；plain-html、vue-chat 计划中。

| 示例 | 技术栈 | 验证什么 | 状态 |
|---|---|---|---|
| `spring-boot-demo` | Spring Boot 2.7.18 + 原生 HTML/JS | SSE 连接、流式、停止、刷新回显、端点开关 | 已实现 |
| `spring-boot-demo-237` | Spring Boot 2.3.7.RELEASE + 原生 HTML/JS | 只认 `spring.factories` 的旧版本能否加载自动配置、端点是否可用 | 已实现 |
| `plain-html` | Spring Boot 2.7 + 原生 HTML/JS | 独立的最小页面（与 demo 拆分） | 计划中 |
| `vue-chat` | Spring Boot 2.7 + Vue 3 | `EventSource` 鉴权、消息列表、停止生成 | 计划中 |

## spring-boot-demo

```bash
# 仓库根目录
mvn -DskipTests install

cd examples/spring-boot-demo
mvn spring-boot:run
```

打开 <http://localhost:8080/chat.html>。

默认 `nova.ai.mock=true`：不访问网络、不需要 API Key，逐字输出用于演示流式与停止。
切换真实模型：

```yaml
nova:
  ai:
    mock: false
    base-url: https://api.deepseek.com
    api-key: ${LLM_API_KEY}
    model: deepseek-chat
```

## spring-boot-demo-237

独立 Maven 工程：parent 固定为 `2.3.7.RELEASE`，不继承仓库根 POM，也不在根 reactor 里。

```bash
# 仓库根目录：先安装 0.1.1 到本地仓库
mvn -DskipTests install

# 构建 + 冒烟测试（断言 /api/ai/chat/online 返回 code=0）
mvn -f examples/spring-boot-demo-237/pom.xml test

# 运行
mvn -f examples/spring-boot-demo-237/pom.xml spring-boot:run
```

打开 <http://localhost:8081/chat.html>（与 2.7 示例同一页面，只用相对路径）。

如果哪天有人把 `META-INF/spring.factories` 删了，`EndpointSmokeTest` 会直接失败——这是 2.3–2.6 兼容性的守门测试。

## 共同约束

- JDK 8。
- Spring Boot 2.3.x–2.7.x（starter 0.1.1 起；0.1.0 仅支持 2.7.x）。
- 不引入 WebFlux。
- API Key 只从环境变量读取。
- 示例默认 `nova.ai.endpoint.enabled=true`，仅用于本地演示。

## 验收标准

- `mvn spring-boot:run` 能启动。
- 浏览器能完成一轮完整对话。
- 点「停止」后，前端在 1 秒内标注「（已停止）」，历史写入已生成部分。
- 刷新页面后，会话历史从服务端回显。
- 关闭页面后，服务端在线数回落。
