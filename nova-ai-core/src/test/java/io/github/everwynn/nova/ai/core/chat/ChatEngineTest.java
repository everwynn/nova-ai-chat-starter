package io.github.everwynn.nova.ai.core.chat;

import io.github.everwynn.nova.ai.core.config.NovaAiSettings;
import io.github.everwynn.nova.ai.core.event.ChatEvent;
import io.github.everwynn.nova.ai.core.llm.CancelSignal;
import io.github.everwynn.nova.ai.core.llm.ChatRequest;
import io.github.everwynn.nova.ai.core.llm.LlmClient;
import io.github.everwynn.nova.ai.core.session.ChatMessage;
import io.github.everwynn.nova.ai.core.session.ChatRole;
import io.github.everwynn.nova.ai.core.session.InMemoryChatSessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 停止语义测试：重点是 stop() 返回时收尾已经完成，不等待 worker / 上游。
 */
class ChatEngineTest {

    private final RecordingGateway gateway = new RecordingGateway();
    private ChatEngine engine;

    @AfterEach
    void tearDown() {
        if (engine != null) {
            engine.close();
        }
    }

    @Test
    void normalCompletionWritesHistoryAndSendsEnd() {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(2)
                .queueCapacity(10)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        engine = new ChatEngine(new SlowLlm("你好世界", 0L), store, gateway, settings);

        engine.send("c1", "s1", "问题");
        await(() -> gateway.hasType(ChatEvent.END), 2000);

        assertThat(gateway.ofType(ChatEvent.STOPPED)).isEmpty();
        List<ChatMessage> history = store.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(0).getRole()).isEqualTo(ChatRole.USER);
        assertThat(history.get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(history.get(1).getContent()).isEqualTo("你好世界");
        assertThat(engine.isRunning("s1")).isFalse();
    }

    @Test
    void stopFinalizesImmediatelyWithoutWaitingWorker() throws Exception {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(2)
                .queueCapacity(10)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        engine = new ChatEngine(new SlowLlm("这是一段用于验证停止行为的较长回答", 40L), store, gateway, settings);

        engine.send("c1", "s1", "问题");
        assertThat(gateway.firstDelta.await(2, TimeUnit.SECONDS)).isTrue();

        long start = System.nanoTime();
        StopResult result = engine.stop("s1");
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

        // stop() 同步完成收尾：事件与历史在返回前已经就位
        assertThat(result.isStopped()).isTrue();
        assertThat(elapsedMillis).isLessThan(500L);
        assertThat(gateway.ofType(ChatEvent.STOPPED)).hasSize(1);
        assertThat(gateway.ofType(ChatEvent.END)).isEmpty();
        assertThat(gateway.ofType(ChatEvent.STOPPED).get(0).getContent()).isNotEmpty();

        List<ChatMessage> history = store.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(history.get(1).getContent()).isNotEmpty();
        assertThat(engine.isRunning("s1")).isFalse();

        // 重复停止无副作用
        assertThat(engine.stop("s1").isStopped()).isFalse();
        assertThat(gateway.ofType(ChatEvent.STOPPED)).hasSize(1);
        assertThat(store.history("s1")).hasSize(2);
    }

    @Test
    void llmErrorDoesNotWriteHistory() {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(2)
                .queueCapacity(10)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        engine = new ChatEngine(new FailingLlm(), store, gateway, settings);

        engine.send("c1", "s1", "问题");
        await(() -> gateway.hasType(ChatEvent.ERROR), 2000);

        assertThat(gateway.ofType(ChatEvent.END)).isEmpty();
        assertThat(gateway.ofType(ChatEvent.STOPPED)).isEmpty();
        assertThat(store.history("s1")).isEmpty();
        assertThat(engine.isRunning("s1")).isFalse();
    }

    @Test
    void rejectsConcurrentSendForSameSession() {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(2)
                .queueCapacity(10)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        engine = new ChatEngine(new SlowLlm("较长的回答内容用于占用任务", 50L), store, gateway, settings);

        engine.send("c1", "s1", "q1");

        assertThatThrownBy(() -> engine.send("c1", "s1", "q2"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("生成中");

        engine.stop("s1");
    }

    @Test
    void rejectsWhenQueueIsFull() throws Exception {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(1)
                .queueCapacity(1)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        BlockingLlm llm = new BlockingLlm();
        engine = new ChatEngine(llm, store, gateway, settings);

        engine.send("c0", "s0", "q0");
        assertThat(llm.started.await(2, TimeUnit.SECONDS)).isTrue();
        engine.send("c1", "s1", "q1");

        assertThatThrownBy(() -> engine.send("c2", "s2", "q2"))
                .isInstanceOf(RejectedExecutionException.class);

        engine.stop("s0");
        engine.stop("s1");
    }

    @Test
    void stopBeforeWorkerStartsDoesNotRequestUpstream() throws Exception {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(1)
                .queueCapacity(10)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        SwitchableLlm llm = new SwitchableLlm();
        engine = new ChatEngine(llm, store, gateway, settings);

        // 占满唯一的 worker，让后续任务停在队列里等待
        engine.send("c0", "s0", "占位");
        assertThat(llm.placeholderStarted.await(2, TimeUnit.SECONDS)).isTrue();

        engine.send("c1", "s1", "真实问题");
        engine.stop("s1");
        Thread.sleep(300L);

        // 任务入口的取消检查生效：不推 start、不请求上游、不写半截历史
        assertThat(llm.otherCalled.get()).isFalse();
        assertThat(store.history("s1")).isEmpty();
        assertThat(engine.isRunning("s1")).isFalse();

        engine.stop("s0");
    }

    @Test
    void closeCancelsRunningGenerationAndPersistsPartial() throws Exception {
        NovaAiSettings settings = NovaAiSettings.builder()
                .model("test")
                .workerThreads(2)
                .queueCapacity(10)
                .build();
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(settings);
        engine = new ChatEngine(new SlowLlm("这是一段用于验证关闭收尾的较长回答内容", 20L), store, gateway, settings);

        engine.send("c1", "s1", "问题");
        assertThat(gateway.firstDelta.await(2, TimeUnit.SECONDS)).isTrue();

        engine.close();

        List<ChatMessage> history = store.history("s1");
        assertThat(history).hasSize(2);
        assertThat(history.get(1).getRole()).isEqualTo(ChatRole.ASSISTANT);
        assertThat(history.get(1).getContent()).isNotEmpty();
        assertThat(engine.isRunning("s1")).isFalse();
    }

    private static void await(BooleanSupplier condition, long timeoutMillis) {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        throw new AssertionError("等待条件超时: " + timeoutMillis + "ms");
    }

    /** 逐字输出，支持间隔；收到取消信号立即返回 */
    private static final class SlowLlm implements LlmClient {

        private final String text;
        private final long intervalMillis;

        private SlowLlm(String text, long intervalMillis) {
            this.text = text;
            this.intervalMillis = intervalMillis;
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

    private static final class FailingLlm implements LlmClient {
        @Override
        public void stream(ChatRequest request, Consumer<String> onDelta, CancelSignal cancelSignal) throws Exception {
            onDelta.accept("半截");
            throw new IllegalStateException("上游连接失败");
        }
    }

    /** 占住 worker 直到被取消，用于验证队列满 */
    private static final class BlockingLlm implements LlmClient {

        private final CountDownLatch started = new CountDownLatch(1);

        @Override
        public void stream(ChatRequest request, Consumer<String> onDelta, CancelSignal cancelSignal) throws Exception {
            started.countDown();
            while (!cancelSignal.isCancelled()) {
                Thread.sleep(20L);
            }
        }
    }

    /** 按提问内容分流：占位任务阻塞，其余任务只记录是否被调用 */
    private static final class SwitchableLlm implements LlmClient {

        private final CountDownLatch placeholderStarted = new CountDownLatch(1);
        private final AtomicBoolean otherCalled = new AtomicBoolean(false);

        @Override
        public void stream(ChatRequest request, Consumer<String> onDelta, CancelSignal cancelSignal) throws Exception {
            if ("占位".equals(firstUserContent(request))) {
                placeholderStarted.countDown();
                while (!cancelSignal.isCancelled()) {
                    onDelta.accept("x");
                    Thread.sleep(30L);
                }
                return;
            }
            otherCalled.set(true);
        }

        private String firstUserContent(ChatRequest request) {
            for (ChatMessage message : request.getMessages()) {
                if (message.getRole() == ChatRole.USER) {
                    return message.getContent();
                }
            }
            return "";
        }
    }

    private static final class RecordingGateway implements ChatGateway {

        private final List<ChatEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstDelta = new CountDownLatch(1);

        @Override
        public boolean send(String clientId, ChatEvent event) {
            events.add(event);
            if (ChatEvent.DELTA.equals(event.getType())) {
                firstDelta.countDown();
            }
            return true;
        }

        @Override
        public boolean isOnline(String clientId) {
            return true;
        }

        @Override
        public int onlineCount() {
            return 1;
        }

        @Override
        public void close(String clientId) {
        }

        private boolean hasType(String type) {
            return events.stream().anyMatch(event -> type.equals(event.getType()));
        }

        private List<ChatEvent> ofType(String type) {
            return events.stream()
                    .filter(event -> type.equals(event.getType()))
                    .collect(Collectors.toList());
        }
    }
}
