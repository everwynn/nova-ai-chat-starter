package io.github.everwynn.nova.ai.core.session;

import io.github.everwynn.nova.ai.core.config.NovaAiSettings;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 会话存储的容量与摘要行为：裁剪、前缀摘要、摘要不随裁剪变化。
 */
class InMemoryChatSessionStoreTest {

    @Test
    void trimsOldestMessagesAndKeepsFirstPreview() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(
                NovaAiSettings.builder().maxTurns(1).build());

        store.append("s1", ChatMessage.user("第一个问题"));
        store.append("s1", ChatMessage.assistant("第一个回答"));
        store.append("s1", ChatMessage.user("第二个问题"));
        store.append("s1", ChatMessage.assistant("第二个回答"));

        assertThat(store.history("s1")).hasSize(2);
        assertThat(store.history("s1").get(0).getContent()).isEqualTo("第二个问题");
        assertThat(store.history("s1").get(1).getContent()).isEqualTo("第二个回答");

        SessionSummary summary = store.list().get(0);
        assertThat(summary.getMessageCount()).isEqualTo(2);
        // 摘要固定为会话第一条提问，裁剪不会把它换掉
        assertThat(summary.getPreview()).isEqualTo("第一个问题");
    }

    @Test
    void previewTruncatesLongQuestion() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(NovaAiSettings.builder().build());
        String longQuestion = "这是一个明显超过三十个字符的提问，用来验证会话摘要会被截断"
                + "并追加省略号，避免会话列表返回完整正文";

        store.append("s1", ChatMessage.user(longQuestion));

        String preview = store.list().get(0).getPreview();
        assertThat(preview).hasSize(31);
        assertThat(preview).endsWith("…");
    }

    @Test
    void startIsIdempotent() {
        InMemoryChatSessionStore store = new InMemoryChatSessionStore(NovaAiSettings.builder().build());
        store.start();
        store.start();
        store.close();
    }
}
