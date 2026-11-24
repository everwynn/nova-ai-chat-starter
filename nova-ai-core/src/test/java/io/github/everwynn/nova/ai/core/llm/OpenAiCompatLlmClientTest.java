package io.github.everwynn.nova.ai.core.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import io.github.everwynn.nova.ai.core.session.ChatMessage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAI 兼容客户端：SSE 帧解析的容错行为。
 */
class OpenAiCompatLlmClientTest {

    private HttpServer server;

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void skipsEmptyDataFramesAndStopsAtDoneMarker() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] body = ("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                    + "data:\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                    + "data: [DONE]\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"迟到\"}}]}\n\n")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });
        server.start();

        OpenAiCompatLlmClient client = new OpenAiCompatLlmClient(
                new ObjectMapper(), baseUrl(), "test-key", "test-model");
        List<String> deltas = new ArrayList<>();

        client.stream(request(), deltas::add, CancelSignal.NONE);

        // 空 data 帧被跳过，[DONE] 之后的帧不再处理
        assertThat(deltas).containsExactly("你", "好");
    }

    @Test
    void doesNotOpenConnectionWhenAlreadyCancelled() throws Exception {
        // 端口 1 不可达：如果实现先连接再检查取消，这里会抛 ConnectException
        OpenAiCompatLlmClient client = new OpenAiCompatLlmClient(
                new ObjectMapper(), "http://127.0.0.1:1", "test-key", "test-model");
        List<String> deltas = new ArrayList<>();
        CancelSignal cancelled = new CancelSignal() {
            @Override
            public boolean isCancelled() {
                return true;
            }

            @Override
            public void onCancel(Runnable hook) {
                hook.run();
            }
        };

        client.stream(request(), deltas::add, cancelled);

        assertThat(deltas).isEmpty();
    }

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private ChatRequest request() {
        return new ChatRequest("test-model", Collections.singletonList(ChatMessage.user("hi")));
    }
}
