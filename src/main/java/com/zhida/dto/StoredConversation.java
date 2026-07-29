package com.zhida.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

public record StoredConversation(
        @NotBlank @Size(max = 100) String id,
        @NotBlank @Size(max = 200) String title,
        @Size(max = 100) String provider,
        @Size(max = 120) String model,
        @Size(max = 40) String createdAt,
        @Size(max = 40) String updatedAt,
        @NotNull @Size(max = 10000) List<@Valid StoredMessage> messages
) {
}
