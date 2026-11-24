package io.github.everwynn.nova.ai.starter;

import io.github.everwynn.nova.ai.core.chat.ChatEngine;
import io.github.everwynn.nova.ai.core.chat.ChatGateway;
import io.github.everwynn.nova.ai.core.event.ChatEvent;
import io.github.everwynn.nova.ai.core.llm.LlmClient;
import io.github.everwynn.nova.ai.starter.web.NovaAiChatController;
import io.github.everwynn.nova.ai.starter.web.SseChatGateway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 两级开关与装配条件测试：默认不装配；enabled 只装配核心 Bean；endpoint 再关一层；非 Web 应用静默降级。
 */
class NovaAiAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    JacksonAutoConfiguration.class,
                    NovaAiAutoConfiguration.class));

    @Test
    void disabledByDefault() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean(ChatEngine.class);
            assertThat(context).doesNotHaveBean(LlmClient.class);
            assertThat(context).doesNotHaveBean(NovaAiChatController.class);
        });
    }

    @Test
    void enabledRegistersCoreBeansButNotEndpoints() {
        runner.withPropertyValues(
                        "nova.ai.enabled=true",
                        "nova.ai.mock=true")
                .run(context -> {
                    assertThat(context).hasSingleBean(ChatEngine.class);
                    assertThat(context).hasSingleBean(LlmClient.class);
                    assertThat(context).doesNotHaveBean(NovaAiChatController.class);
                });
    }

    @Test
    void endpointRequiresSecondSwitch() {
        runner.withPropertyValues(
                        "nova.ai.enabled=true",
                        "nova.ai.mock=true",
                        "nova.ai.endpoint.enabled=true")
                .run(context -> assertThat(context).hasSingleBean(NovaAiChatController.class));
    }

    @Test
    void silentlyBacksOffOutsideServletWebApplication() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        JacksonAutoConfiguration.class,
                        NovaAiAutoConfiguration.class))
                .withPropertyValues(
                        "nova.ai.enabled=true",
                        "nova.ai.mock=true",
                        "nova.ai.endpoint.enabled=true")
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ChatEngine.class);
                    assertThat(context).doesNotHaveBean(NovaAiChatController.class);
                });
    }

    @Test
    void primaryCustomGatewayTakesOverEngineAndKeepsBuiltInEndpoint() {
        runner.withPropertyValues(
                        "nova.ai.enabled=true",
                        "nova.ai.mock=true",
                        "nova.ai.endpoint.enabled=true")
                .withUserConfiguration(CustomGatewayConfiguration.class)
                .run(context -> {
                    RecordingGateway custom = context.getBean(RecordingGateway.class);
                    // @Primary 生效：引擎注入的是自定义实现
                    assertThat(context.getBean(ChatGateway.class)).isSameAs(custom);
                    // 内置 SSE gateway 与端点仍然存在，自定义实现可以包装它做双写
                    assertThat(context).hasSingleBean(SseChatGateway.class);
                    assertThat(context).hasSingleBean(NovaAiChatController.class);

                    ChatEngine engine = context.getBean(ChatEngine.class);
                    engine.send("c1", "s1", "hello");
                    long deadline = System.currentTimeMillis() + 2000L;
                    while (!custom.started && System.currentTimeMillis() < deadline) {
                        Thread.sleep(10L);
                    }
                    engine.stop("s1");
                    assertThat(custom.started).isTrue();
                });
    }

    @Configuration(proxyBeanMethods = false)
    static class CustomGatewayConfiguration {

        @Bean
        @Primary
        RecordingGateway customChatGateway() {
            return new RecordingGateway();
        }
    }

    static class RecordingGateway implements ChatGateway {

        private final List<ChatEvent> events = new CopyOnWriteArrayList<>();
        private volatile boolean started;

        @Override
        public boolean send(String clientId, ChatEvent event) {
            events.add(event);
            if (ChatEvent.START.equals(event.getType())) {
                started = true;
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
            // 测试实现无需释放资源
        }
    }
}
