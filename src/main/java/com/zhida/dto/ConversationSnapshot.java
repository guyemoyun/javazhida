package com.zhida.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ConversationSnapshot(
        int version,
        @NotNull @Size(max = 2000) List<@Valid StoredConversation> conversations
) {
    public static final int CURRENT_VERSION = 1;

    public ConversationSnapshot(List<StoredConversation> conversations) {
        this(CURRENT_VERSION, conversations);
    }
}
