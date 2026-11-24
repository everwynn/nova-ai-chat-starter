package io.github.everwynn.nova.ai.starter;

import io.github.everwynn.nova.ai.core.config.NovaAiSettings;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code nova.ai.*} 配置项。
 */
@ConfigurationProperties(prefix = "nova.ai")
public class NovaAiProperties {

    /** 总开关，默认关闭：不装配任何核心 Bean */
    private boolean enabled = false;

    /** OpenAI 兼容服务根地址，如 https://api.deepseek.com（也允许带 /v1） */
    private String baseUrl = "";
    /** API Key，建议通过环境变量注入 */
    private String apiKey = "";
    /** 模型名 */
    private String model = "";
    /** 系统提示词，可空 */
    private String systemPrompt = "";

    /** 每个会话保留的历史轮数 */
    private int maxTurns = 10;
    /** 会话空闲过期时间（分钟） */
    private long ttlMinutes = 120L;
    /** 内存会话上限 */
    private int maxSessions = 1000;

    /** 会话存储：memory（v0.1 内置）或自定义 ChatSessionStore Bean */
    private String sessionStore = "memory";

    /** 生成线程数 */
    private int workerThreads = 4;
    /** 有界队列长度，满后返回「服务繁忙」 */
    private int queueCapacity = 200;

    /** hard=断开上游（默认）；soft=不打断上游、只丢弃迟到增量 */
    private String stopMode = "hard";

    /** 本地演示模式：不访问网络，内置逐字输出 */
    private boolean mock = false;
    /** Mock 模式下每个字符的输出间隔（毫秒） */
    private long mockIntervalMillis = 40L;

    /** SSE 连接超时（毫秒），0 表示不超时 */
    private long sseTimeoutMillis = 0L;
    /** 心跳间隔（毫秒） */
    private long heartbeatMillis = 15000L;

    /** HTTP 端点开关，默认关闭 */
    private Endpoint endpoint = new Endpoint();

    public NovaAiSettings toSettings() {
        return NovaAiSettings.builder()
                .model(model)
                .systemPrompt(systemPrompt)
                .maxTurns(maxTurns)
                .ttlMinutes(ttlMinutes)
                .maxSessions(maxSessions)
                .workerThreads(workerThreads)
                .queueCapacity(queueCapacity)
                .stopMode(stopMode)
                .build();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public void setTtlMinutes(long ttlMinutes) {
        this.ttlMinutes = ttlMinutes;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public String getSessionStore() {
        return sessionStore;
    }

    public void setSessionStore(String sessionStore) {
        this.sessionStore = sessionStore;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public void setWorkerThreads(int workerThreads) {
        this.workerThreads = workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public void setQueueCapacity(int queueCapacity) {
        this.queueCapacity = queueCapacity;
    }

    public String getStopMode() {
        return stopMode;
    }

    public void setStopMode(String stopMode) {
        this.stopMode = stopMode;
    }

    public boolean isMock() {
        return mock;
    }

    public void setMock(boolean mock) {
        this.mock = mock;
    }

    public long getMockIntervalMillis() {
        return mockIntervalMillis;
    }

    public void setMockIntervalMillis(long mockIntervalMillis) {
        this.mockIntervalMillis = mockIntervalMillis;
    }

    public long getSseTimeoutMillis() {
        return sseTimeoutMillis;
    }

    public void setSseTimeoutMillis(long sseTimeoutMillis) {
        this.sseTimeoutMillis = sseTimeoutMillis;
    }

    public long getHeartbeatMillis() {
        return heartbeatMillis;
    }

    public void setHeartbeatMillis(long heartbeatMillis) {
        this.heartbeatMillis = heartbeatMillis;
    }

    public Endpoint getEndpoint() {
        return endpoint;
    }

    public void setEndpoint(Endpoint endpoint) {
        this.endpoint = endpoint;
    }

    public static class Endpoint {

        /** 是否注册 /api/ai/chat/* 端点，默认关闭 */
        private boolean enabled = false;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
