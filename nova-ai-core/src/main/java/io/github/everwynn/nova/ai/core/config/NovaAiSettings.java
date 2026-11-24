package io.github.everwynn.nova.ai.core.config;

/**
 * 与框架无关的核心配置模型，由 Starter 的 {@code nova.ai.*} 属性映射而来。
 */
public final class NovaAiSettings {

    private final String model;
    private final String systemPrompt;
    private final int maxTurns;
    private final long ttlMinutes;
    private final int maxSessions;
    private final int workerThreads;
    private final int queueCapacity;
    private final String stopMode;

    private NovaAiSettings(Builder builder) {
        this.model = builder.model;
        this.systemPrompt = builder.systemPrompt;
        this.maxTurns = builder.maxTurns;
        this.ttlMinutes = builder.ttlMinutes;
        this.maxSessions = builder.maxSessions;
        this.workerThreads = builder.workerThreads;
        this.queueCapacity = builder.queueCapacity;
        this.stopMode = builder.stopMode;
    }

    public static Builder builder() {
        return new Builder();
    }

    /** hard=断开上游（默认）；soft=不打断上游、只丢弃迟到增量 */
    public boolean isHardStop() {
        return !"soft".equalsIgnoreCase(stopMode);
    }

    public String getModel() {
        return model;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public int getMaxTurns() {
        return maxTurns;
    }

    public long getTtlMinutes() {
        return ttlMinutes;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public int getWorkerThreads() {
        return workerThreads;
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public String getStopMode() {
        return stopMode;
    }

    public static final class Builder {

        private String model = "";
        private String systemPrompt = "";
        private int maxTurns = 10;
        private long ttlMinutes = 120L;
        private int maxSessions = 1000;
        private int workerThreads = 4;
        private int queueCapacity = 200;
        private String stopMode = "hard";

        public Builder model(String model) {
            this.model = model == null ? "" : model;
            return this;
        }

        public Builder systemPrompt(String systemPrompt) {
            this.systemPrompt = systemPrompt == null ? "" : systemPrompt;
            return this;
        }

        public Builder maxTurns(int maxTurns) {
            this.maxTurns = Math.max(1, maxTurns);
            return this;
        }

        public Builder ttlMinutes(long ttlMinutes) {
            this.ttlMinutes = Math.max(1L, ttlMinutes);
            return this;
        }

        public Builder maxSessions(int maxSessions) {
            this.maxSessions = Math.max(1, maxSessions);
            return this;
        }

        public Builder workerThreads(int workerThreads) {
            this.workerThreads = Math.max(1, workerThreads);
            return this;
        }

        public Builder queueCapacity(int queueCapacity) {
            this.queueCapacity = Math.max(1, queueCapacity);
            return this;
        }

        public Builder stopMode(String stopMode) {
            this.stopMode = stopMode == null ? "hard" : stopMode;
            return this;
        }

        public NovaAiSettings build() {
            return new NovaAiSettings(this);
        }
    }
}
