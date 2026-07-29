package com.zhida.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

public record ChatRequest(
        @NotBlank(message = "请选择模型服务商") String provider,
        @Size(max = 120, message = "模型名称不能超过 120 个字符") String model,
        @NotBlank(message = "请输入问题") @Size(max = 12000, message = "问题不能超过 12000 个字符") String message,
        @Size(max = 30, message = "历史消息不能超过 30 条") List<@Valid ChatMessage> history
) {
}
