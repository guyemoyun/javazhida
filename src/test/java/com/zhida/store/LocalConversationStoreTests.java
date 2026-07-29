package com.zhida.store;

import com.zhida.dto.ConversationSnapshot;
import com.zhida.dto.StoredConversation;
import com.zhida.dto.StoredMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalConversationStoreTests {
    @TempDir
    Path directory;

    @Test
    void persistsAndLoadsCompleteConversation() {
        LocalConversationStore store = new LocalConversationStore(directory.toString());
        StoredMessage user = new StoredMessage(
                "message-user", "user", "你好", "", "", "2026-07-29T08:00:00Z", "complete");
        StoredMessage assistant = new StoredMessage(
                "message-assistant", "assistant", "你好，有什么可以帮你？", "deepseek", "deepseek-chat",
                "2026-07-29T08:00:01Z", "complete");
        StoredConversation conversation = new StoredConversation(
                "conversation-1", "问候", "deepseek", "deepseek-chat",
                "2026-07-29T08:00:00Z", "2026-07-29T08:00:01Z", List.of(user, assistant));

        store.replace(new ConversationSnapshot(List.of(conversation)));

        ConversationSnapshot loaded = store.load();
        assertEquals(ConversationSnapshot.CURRENT_VERSION, loaded.version());
        assertEquals(List.of(conversation), loaded.conversations());
    }

    @Test
    void replaceIsAuthoritativeAndInterruptedStreamsBecomeStopped() {
        LocalConversationStore store = new LocalConversationStore(directory.toString());
        StoredMessage streaming = new StoredMessage(
                "message-1", "assistant", "部分回答", "deepseek", "deepseek-chat",
                "2026-07-29T08:00:00Z", "streaming");
        StoredConversation conversation = new StoredConversation(
                "conversation-1", "测试", "deepseek", "deepseek-chat",
                "2026-07-29T08:00:00Z", "2026-07-29T08:00:00Z", List.of(streaming));
        store.replace(new ConversationSnapshot(List.of(conversation)));

        assertEquals("stopped", store.load().conversations().get(0).messages().get(0).status());

        store.replace(new ConversationSnapshot(List.of()));
        assertTrue(store.load().conversations().isEmpty());
    }
}
