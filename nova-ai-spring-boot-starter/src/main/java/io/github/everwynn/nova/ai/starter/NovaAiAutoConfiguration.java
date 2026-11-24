package io.github.everwynn.nova.ai.starter;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.everwynn.nova.ai.core.chat.ChatEngine;
import io.github.everwynn.nova.ai.core.chat.ChatGateway;
import io.github.everwynn.nova.ai.core.llm.LlmClient;
import io.github.everwynn.nova.ai.core.llm.MockLlmClient;
import io.github.everwynn.nova.ai.core.llm.OpenAiCompatLlmClient;
import io.github.everwynn.nova.ai.core.session.ChatSessionStore;
import io.github.everwynn.nova.ai.core.session.InMemoryChatSessionStore;
import io.github.everwynn.nova.ai.starter.web.NovaAiChatController;
import io.github.everwynn.nova.ai.starter.web.SseChatGateway;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 自动配置入口。
 *
 * <p>两级开关：
 * <ul>
 *   <li>{@code nova.ai.enabled=true} 才装配核心 Bean；</li>
 *   <li>{@code nova.ai.endpoint.enabled=true} 才注册 HTTP 端点。</li>
 * </ul>
 * 用户自定义的 {@code LlmClient} / {@code ChatSessionStore} / {@code ChatGateway}
 * （需 {@code @Primary}）不会被覆盖。
 *
 * <p>非 Servlet Web 应用或缺少 Jackson / Spring MVC 时静默退场，不影响应用启动。
 */
@AutoConfiguration(after = JacksonAutoConfiguration.class)
@ConditionalOnClass({SseEmitter.class, ObjectMapper.class})
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
@ConditionalOnProperty(prefix = "nova.ai", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(NovaAiProperties.class)
public class NovaAiAutoConfiguration {

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(ChatSessionStore.class)
    @ConditionalOnProperty(prefix = "nova.ai", name = "session-store",
            havingValue = "memory", matchIfMissing = true)
    public InMemoryChatSessionStore novaAiChatSessionStore(NovaAiProperties properties) {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(properties.toSettings());
        store.start();
        return store;
    }

    @Bean
    @ConditionalOnMissingBean(LlmClient.class)
    public LlmClient novaAiLlmClient(NovaAiProperties properties, ObjectMapper objectMapper) {
        if (properties.isMock()) {
            return new MockLlmClient(properties.getMockIntervalMillis());
        }
        return new OpenAiCompatLlmClient(objectMapper, properties.getBaseUrl(),
                properties.getApiKey(), properties.getModel());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public SseChatGateway novaAiSseChatGateway(NovaAiProperties properties, ObjectMapper objectMapper) {
        return new SseChatGateway(objectMapper,
                properties.getSseTimeoutMillis(), properties.getHeartbeatMillis());
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    public ChatEngine novaAiChatEngine(LlmClient llmClient,
                                       ChatSessionStore sessionStore,
                                       ChatGateway gateway,
                                       NovaAiProperties properties) {
        return new ChatEngine(llmClient, sessionStore, gateway, properties.toSettings());
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnProperty(prefix = "nova.ai.endpoint", name = "enabled", havingValue = "true")
    public NovaAiChatController novaAiChatController(ChatEngine engine, SseChatGateway gateway) {
        return new NovaAiChatController(engine, gateway);
    }
}
