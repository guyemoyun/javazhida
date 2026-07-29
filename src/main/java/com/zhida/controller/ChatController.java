package com.zhida.controller;

import com.zhida.common.Result;
import com.zhida.dto.ChatRequest;
import com.zhida.dto.ChatResponse;
import com.zhida.dto.ProviderInfo;
import com.zhida.service.ChatService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;

@RestController
public class ChatController {
    private final ChatService chatService;

    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/api/providers")
    public Result<List<ProviderInfo>> providers() {
        return Result.ok(chatService.providers());
    }

    @PostMapping("/api/chat")
    public Result<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return Result.ok(chatService.chat(request));
    }

    @PostMapping(value = "/api/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        SseEmitter emitter = new SseEmitter(95_000L);
        AtomicBoolean closed = new AtomicBoolean(false);
        ChatService.ChatStream stream = chatService.stream(request, event -> {
            if (closed.get()) {
                throw new IOException("客户端已断开");
            }
            emitter.send(SseEmitter.event().name(event.type()).data(event));
        });

        emitter.onCompletion(() -> cancelIfOpen(closed, stream));
        emitter.onTimeout(() -> cancelIfOpen(closed, stream));
        emitter.onError(ignored -> cancelIfOpen(closed, stream));
        stream.completion().whenComplete((ignored, exception) -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            if (exception != null) {
                try {
                    emitter.send(SseEmitter.event().name("error")
                            .data(Map.of("message", streamErrorMessage(exception))));
                } catch (IOException ignoredError) {
                }
            }
            emitter.complete();
        });
        return emitter;
    }

    private void cancelIfOpen(AtomicBoolean closed, ChatService.ChatStream stream) {
        if (closed.compareAndSet(false, true)) {
            stream.cancel();
        }
    }

    private String streamErrorMessage(Throwable exception) {
        Throwable cause = exception;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null || cause.getMessage().isBlank()
                ? "模型流式请求失败"
                : cause.getMessage();
    }
}
