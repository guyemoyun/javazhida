package com.zhida.service;

import com.zhida.config.ChatProperties;
import com.zhida.dto.ChatMessage;
import com.zhida.dto.ChatRequest;
import com.zhida.dto.ChatResponse;
import com.zhida.dto.ProviderInfo;
import com.zhida.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ChatService {
    private final ChatProperties properties;
    private final HttpClient httpClient;
    private final JsonMapper objectMapper;

    public ChatService(ChatProperties properties, HttpClient httpClient, JsonMapper objectMapper) {
        this.properties = properties;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public List<ProviderInfo> providers() {
        return properties.getProviders().entrySet().stream()
                .map(entry -> toInfo(entry.getKey(), entry.getValue()))
                .toList();
    }

    public ChatResponse chat(ChatRequest request) {
        ChatProperties.Provider provider = properties.getProviders().get(request.provider());
        if (provider == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "未知的模型服务商");
        }
        if (isBlank(provider.getApiKey())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "尚未配置 " + provider.getName() + " 的 API 密钥");
        }
        if (isBlank(provider.getBaseUrl())) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE,
                    "尚未配置 " + provider.getName() + " 的服务地址");
        }

        String model = isBlank(request.model()) ? provider.getModel() : request.model().trim();
        if (isBlank(model)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "请填写模型名称");
        }

        try {
            HttpRequest httpRequest = HttpRequest.newBuilder(chatCompletionsUri(provider.getBaseUrl()))
                    .timeout(Duration.ofSeconds(90))
                    .header("Authorization", "Bearer " + provider.getApiKey().trim())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload(request, model))))
                    .build();
            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, provider.getName() + " 请求失败：" + providerError(response.body()));
            }
            String answer = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText();
            if (isBlank(answer)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, provider.getName() + " 未返回有效回答");
            }
            return new ChatResponse(request.provider(), model, answer);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "无法解析模型服务响应");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "模型请求已中断");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "模型服务地址无效");
        }
    }

    private ProviderInfo toInfo(String id, ChatProperties.Provider provider) {
        return new ProviderInfo(id, provider.getName(), provider.getBaseUrl(), provider.getModel(), !isBlank(provider.getApiKey()));
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        return URI.create(normalized + "/chat/completions");
    }

    private Map<String, Object> payload(ChatRequest request, String model) {
        List<Map<String, String>> messages = new ArrayList<>();
        if (request.history() != null) {
            for (ChatMessage message : request.history()) {
                messages.add(Map.of("role", message.role(), "content", message.content()));
            }
        }
        messages.add(Map.of("role", "user", "content", request.message().trim()));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("messages", messages);
        body.put("stream", false);
        return body;
    }

    private String providerError(String body) {
        try {
            JsonNode node = objectMapper.readTree(body);
            String message = node.path("error").path("message").asText();
            return isBlank(message) ? "上游服务返回 HTTP 错误" : message;
        } catch (RuntimeException ignored) {
            return "上游服务返回 HTTP 错误";
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
