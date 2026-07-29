package com.zhida.service;

import com.sun.net.httpserver.HttpServer;
import com.zhida.config.ChatProperties;
import com.zhida.dto.ChatRequest;
import com.zhida.dto.ChatStreamEvent;
import com.zhida.exception.ApiException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceStreamingTests {
    private HttpServer server;
    private final AtomicReference<String> requestBody = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    @Test
    void normalizesOpenAiCompatibleEventStream() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n"
                    + "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n"
                    + "data: [DONE]\n\n").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream; charset=utf-8");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ChatService chatService = chatService();
        List<ChatStreamEvent> events = new ArrayList<>();

        ChatService.ChatStream stream = chatService.stream(
                new ChatRequest("test", "test-model", "继续", List.of()), events::add);
        stream.completion().get(5, TimeUnit.SECONDS);

        assertEquals(List.of("meta", "delta", "delta", "done"),
                events.stream().map(ChatStreamEvent::type).toList());
        assertEquals("你好", events.get(1).content() + events.get(2).content());
        assertTrue(requestBody.get().contains("\"stream\":true"));
        assertTrue(requestBody.get().contains("\"content\":\"继续\""));
    }

    @Test
    void exposesUpstreamErrorMessage() throws Exception {
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = "{\"error\":{\"message\":\"余额不足\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        ChatService.ChatStream stream = chatService().stream(
                new ChatRequest("test", "test-model", "继续", List.of()), ignored -> { });

        ExecutionException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                ExecutionException.class, () -> stream.completion().get(5, TimeUnit.SECONDS));

        Throwable cause = thrown.getCause();
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        assertInstanceOf(ApiException.class, cause);
        assertTrue(cause.getMessage().contains("余额不足"));
    }

    private ChatService chatService() {
        ChatProperties.Provider provider = new ChatProperties.Provider();
        provider.setName("测试模型");
        provider.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
        provider.setApiKey("test-key");
        provider.setModel("test-model");
        ChatProperties properties = new ChatProperties();
        LinkedHashMap<String, ChatProperties.Provider> providers = new LinkedHashMap<>();
        providers.put("test", provider);
        properties.setProviders(providers);
        properties.setDefaultProvider("test");
        return new ChatService(properties, HttpClient.newHttpClient(), JsonMapper.builder().build());
    }
}
