package com.zhida.controller;


import com.zhida.common.Result;
import com.zhida.dto.ChatRequest;
import com.zhida.dto.ChatResponse;
import com.zhida.dto.ProviderInfo;
import com.zhida.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/api/providers")
    public Result<java.util.List<ProviderInfo>> providers() {
        return Result.ok(chatService.providers());
    }

    @PostMapping("/api/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest req){
        return Result.ok(chatService.chat(req));
    }

}
