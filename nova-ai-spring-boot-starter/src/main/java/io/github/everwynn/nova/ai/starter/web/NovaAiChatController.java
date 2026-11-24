package io.github.everwynn.nova.ai.starter.web;

import io.github.everwynn.nova.ai.core.chat.ChatEngine;
import io.github.everwynn.nova.ai.core.chat.StopResult;
import io.github.everwynn.nova.ai.core.session.ChatMessage;
import io.github.everwynn.nova.ai.core.session.SessionSummary;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

/**
 * 默认对话端点，仅在 {@code nova.ai.endpoint.enabled=true} 时注册。
 *
 * <p>鉴权交给使用方的 Spring Security 或网关；{@code EventSource} 不能自定义 Header，
 * 推荐同源 Cookie 或一次性 ticket 方案。
 */
@RestController
@RequestMapping("/api/ai/chat")
public class NovaAiChatController {

    private static final Logger logger = LoggerFactory.getLogger(NovaAiChatController.class);

    private static final int MAX_ID_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 4000;
    private static final Pattern ID_PATTERN = Pattern.compile("[A-Za-z0-9_-]+");

    private final ChatEngine engine;
    private final SseChatGateway gateway;

    public NovaAiChatController(ChatEngine engine, SseChatGateway gateway) {
        this.engine = engine;
        this.gateway = gateway;
    }

    /** 建立 SSE 长连接；clientId 非法返回 400，避免 EventSource 对 500 反复重连 */
    @GetMapping(value = "/connect/{clientId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter connect(@PathVariable String clientId) {
        if (!isValidId(clientId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "clientId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        return gateway.connect(clientId);
    }

    /** 发送消息：立即返回，回答通过 SSE 推送 */
    @PostMapping("/send")
    public Result<String> send(@RequestBody ChatSendRequest request) {
        if (request == null) {
            return Result.fail("请求体不能为空");
        }
        String clientId = trim(request.getClientId());
        String sessionId = trim(request.getSessionId());
        String message = trim(request.getMessage());
        if (clientId.isEmpty()) {
            return Result.fail("clientId不能为空");
        }
        if (message.isEmpty()) {
            return Result.fail("message不能为空");
        }
        if (!isValidId(clientId)) {
            return Result.fail("clientId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        if (sessionId.isEmpty()) {
            sessionId = clientId;
        }
        if (!isValidId(sessionId)) {
            return Result.fail("sessionId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            return Result.fail("message 超长（上限 " + MAX_MESSAGE_LENGTH + " 字符）");
        }
        try {
            engine.send(clientId, sessionId, message);
        } catch (RejectedExecutionException e) {
            return Result.fail("服务繁忙，请稍后重试");
        } catch (IllegalStateException e) {
            return Result.fail(e.getMessage());
        } catch (Exception e) {
            logger.error("发送消息失败, clientId={}, sessionId={}", clientId, sessionId, e);
            return Result.fail("发送失败：" + e.getMessage());
        }
        return Result.ok("已开始生成");
    }

    /** 停止生成：返回时前端收尾事件已发出 */
    @PostMapping("/stop/{sessionId}")
    public Result<String> stop(@PathVariable String sessionId) {
        String sid = trim(sessionId);
        if (!isValidId(sid)) {
            return Result.fail("sessionId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        StopResult result = engine.stop(sid);
        if (!result.isStopped()) {
            return Result.fail("当前会话没有生成中的回答");
        }
        return Result.ok("已请求停止");
    }

    /** 清空会话历史 */
    @PostMapping("/session/{sessionId}/clear")
    public Result<String> clear(@PathVariable String sessionId) {
        String sid = trim(sessionId);
        if (!isValidId(sid)) {
            return Result.fail("sessionId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        engine.clear(sid);
        return Result.ok("会话已清空");
    }

    /** 查询会话历史，供刷新/换设备回显；会话不存在时返回空数组 */
    @GetMapping("/history/{sessionId}")
    public Result<List<ChatMessage>> history(@PathVariable String sessionId) {
        String sid = trim(sessionId);
        if (!isValidId(sid)) {
            return Result.fail("sessionId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        return Result.ok(engine.history(sid));
    }

    /** 会话列表：摘要 + 条数 + 最后活跃时间 */
    @GetMapping("/sessions")
    public Result<List<SessionSummary>> sessions() {
        return Result.ok(engine.sessions());
    }

    /** 删除会话，与 clear 语义相同 */
    @DeleteMapping("/session/{sessionId}")
    public Result<String> delete(@PathVariable String sessionId) {
        String sid = trim(sessionId);
        if (!isValidId(sid)) {
            return Result.fail("sessionId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        engine.clear(sid);
        return Result.ok("会话已删除");
    }

    /** 主动关闭 SSE 连接 */
    @PostMapping("/close/{clientId}")
    public Result<String> close(@PathVariable String clientId) {
        String cid = trim(clientId);
        if (!isValidId(cid)) {
            return Result.fail("clientId 格式非法（1-" + MAX_ID_LENGTH + " 位字母/数字/下划线/中划线）");
        }
        gateway.close(cid);
        return Result.ok("连接关闭");
    }

    /** 在线连接数 */
    @GetMapping("/online")
    public Result<Integer> online() {
        return Result.ok(engine.onlineCount());
    }

    private static boolean isValidId(String value) {
        return value != null && !value.isEmpty()
                && value.length() <= MAX_ID_LENGTH && ID_PATTERN.matcher(value).matches();
    }

    private static String trim(String value) {
        return value == null ? "" : value.trim();
    }
}
