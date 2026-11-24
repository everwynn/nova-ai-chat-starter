package io.github.everwynn.nova.ai.core.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.everwynn.nova.ai.core.session.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * OpenAI 兼容流式客户端：读取 {@code /v1/chat/completions} 的 SSE 流，逐帧解析增量。
 *
 * <p>适用于 OpenAI / DeepSeek / 通义兼容模式 / vLLM / Ollama 等兼容服务。
 *
 * <p>取消说明：{@code HttpURLConnection.disconnect()} 在 JDK8 下不能保证立即打断阻塞中的
 * {@code readLine()}，因此断开只是 best-effort（用于尽快中止上游计费）。前端收尾由
 * {@code ChatEngine.stop} 立即完成，不依赖这里返回。
 */
public class OpenAiCompatLlmClient implements LlmClient {

    private static final Logger logger = LoggerFactory.getLogger(OpenAiCompatLlmClient.class);

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    /** 流式长连接，读超时放宽 */
    private static final int READ_TIMEOUT_MILLIS = 300_000;

    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public OpenAiCompatLlmClient(ObjectMapper objectMapper, String baseUrl, String apiKey, String model) {
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl == null ? "" : baseUrl.trim();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.model = model == null ? "" : model.trim();
    }

    @Override
    public void stream(ChatRequest request, Consumer<String> onDelta, CancelSignal cancelSignal) throws Exception {
        if (request == null || request.getMessages().isEmpty()) {
            throw new IllegalArgumentException("messages不能为空");
        }
        if (baseUrl.isEmpty()) {
            throw new IllegalStateException("nova.ai.base-url 未配置");
        }
        if (apiKey.isEmpty()) {
            throw new IllegalStateException("nova.ai.api-key 未配置，建议通过环境变量注入");
        }
        if (request.getModel().isEmpty()) {
            throw new IllegalStateException("nova.ai.model 未配置");
        }
        // 任务在提交后、真正发起请求前被 stop 时，不再建立上游连接
        if (cancelSignal.isCancelled()) {
            logger.info("LLM调用前已取消, model={}", model);
            return;
        }

        HttpURLConnection conn = (HttpURLConnection) new URL(completionsUrl()).openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Authorization", "Bearer " + apiKey);
        conn.setDoOutput(true);
        conn.setConnectTimeout(CONNECT_TIMEOUT_MILLIS);
        conn.setReadTimeout(READ_TIMEOUT_MILLIS);

        // 取消时尝试断开连接；JDK8 下不保证立即打断阻塞读，仅用于尽快中止上游
        cancelSignal.onCancel(conn::disconnect);
        try {
            byte[] payload = objectMapper.writeValueAsBytes(buildPayload(request));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload);
            }

            int code = conn.getResponseCode();
            if (code != HttpURLConnection.HTTP_OK) {
                String detail = readErrorBody(conn);
                throw new IOException("LLM接口返回异常, HTTP " + code
                        + (detail.isEmpty() ? "" : ", body=" + detail));
            }

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (cancelSignal.isCancelled()) {
                        logger.info("LLM流式读取收到取消信号, model={}", model);
                        return;
                    }
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.isEmpty()) {
                        // 部分服务会发空 data 作为保活帧，跳过而不是当成 JSON 解析
                        continue;
                    }
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode delta = objectMapper.readTree(data)
                            .path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode() && !delta.isNull()) {
                        String text = delta.asText();
                        if (!text.isEmpty()) {
                            onDelta.accept(text);
                        }
                    }
                }
            }
        } catch (IOException e) {
            if (cancelSignal.isCancelled()) {
                logger.info("LLM流式读取被取消, model={}, reason={}", model, e.getMessage());
                return;
            }
            throw e;
        } finally {
            conn.disconnect();
        }
    }

    private Map<String, Object> buildPayload(ChatRequest request) {
        List<Map<String, Object>> messages = new ArrayList<>();
        for (ChatMessage message : request.getMessages()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getRole().wireName());
            item.put("content", message.getContent());
            messages.add(item);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", request.getModel());
        body.put("stream", true);
        body.put("messages", messages);
        return body;
    }

    /** base-url 允许写根地址或已带 /v1 的地址 */
    private String completionsUrl() {
        String base = baseUrl;
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        if (base.endsWith("/v1")) {
            return base + "/chat/completions";
        }
        return base + "/v1/chat/completions";
    }

    /** 读取上游错误响应体（截断 500 字符），仅用于异常消息 */
    private String readErrorBody(HttpURLConnection conn) {
        InputStream errorStream = conn.getErrorStream();
        if (errorStream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(errorStream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[256];
            int n;
            while (sb.length() < 500 && (n = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, n);
            }
        } catch (IOException e) {
            return "";
        }
        String text = sb.toString().replaceAll("\\s+", " ").trim();
        return text.length() > 500 ? text.substring(0, 500) + "..." : text;
    }
}
