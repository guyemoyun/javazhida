package com.zhida.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record StoredMessage(
        @NotBlank @Size(max = 100) String id,
        @Pattern(regexp = "user|assistant", message = "消息角色不合法") String role,
        @NotBlank @Size(max = 200000) String content,
        @Size(max = 100) String provider,
        @Size(max = 120) String model,
        @Size(max = 40) String createdAt,
        @Pattern(regexp = "complete|streaming|stopped|error|failed", message = "消息状态不合法") String status
) {
}
