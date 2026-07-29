package com.zhida.service;

import com.zhida.config.ChatProperties;
import com.zhida.dto.ChatMessage;
import com.zhida.dto.ChatRequest;
import com.zhida.dto.ChatResponse;
import com.zhida.dto.ChatStreamEvent;
import com.zhida.dto.ProviderInfo;
import com.zhida.exception.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
        List<ProviderInfo> providers = new ArrayList<>();
        String defaultProvider = properties.getDefaultProvider();
        ChatProperties.Provider configuredDefault = properties.getProviders().get(defaultProvider);
        if (configuredDefault != null) {
            providers.add(toInfo(defaultProvider, configuredDefault));
        }
        properties.getProviders().forEach((id, provider) -> {
            if (!id.equals(defaultProvider)) {
                providers.add(toInfo(id, provider));
            }
        });
        return providers;
    }

    public ChatResponse chat(ChatRequest request) {
        PreparedChat prepared = prepare(request);
        try {
            HttpResponse<String> response = httpClient.send(
                    upstreamRequest(request, prepared, false), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw upstreamFailure(prepared, response.body());
            }
            String answer = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content").asText();
            if (isBlank(answer)) {
                throw new ApiException(HttpStatus.BAD_GATEWAY, prepared.provider().getName() + " 未返回有效回答");
            }
            return new ChatResponse(request.provider(), prepared.model(), answer);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_GATEWAY, "无法解析模型服务响应");
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ApiException(HttpStatus.GATEWAY_TIMEOUT, "模型请求已中断");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "模型服务地址无效");
        }
    }

    public ChatStream stream(ChatRequest request, StreamListener listener) {
        PreparedChat prepared = prepare(request);
        HttpRequest upstream;
        try {
            upstream = upstreamRequest(request, prepared, true);
        } catch (IOException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "无法创建模型请求");
        } catch (IllegalArgumentException exception) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "模型服务地址无效");
        }

        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<InputStream> responseBody = new AtomicReference<>();
        CompletableFuture<HttpResponse<InputStream>> responseFuture =
                httpClient.sendAsync(upstream, HttpResponse.BodyHandlers.ofInputStream());
        CompletableFuture<Void> completion = responseFuture.thenAcceptAsync(response -> {
            responseBody.set(response.body());
            try (InputStream body = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    throw upstreamFailure(prepared, new String(body.readAllBytes(), StandardCharsets.UTF_8));
                }
                listener.onEvent(ChatStreamEvent.meta(request.provider(), prepared.model()));
                readEventStream(body, listener, cancelled);
                if (!cancelled.get()) {
                    listener.onEvent(ChatStreamEvent.done());
                }
            } catch (IOException exception) {
                if (!cancelled.get()) {
                    throw new CompletionException(new ApiException(
                            HttpStatus.BAD_GATEWAY, "模型流式响应读取失败"));
                }
            }
        });

        return new ChatStream(completion, () -> {
            cancelled.set(true);
            closeQuietly(responseBody.get());
            responseFuture.cancel(true);
            completion.cancel(true);
        });
    }

    private PreparedChat prepare(ChatRequest request) {
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
        return new PreparedChat(provider, model);
    }

    private HttpRequest upstreamRequest(ChatRequest request, PreparedChat prepared, boolean stream) throws IOException {
        return HttpRequest.newBuilder(chatCompletionsUri(prepared.provider().getBaseUrl()))
                .timeout(Duration.ofSeconds(90))
                .header("Authorization", "Bearer " + prepared.provider().getApiKey().trim())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        objectMapper.writeValueAsString(payload(request, prepared.model(), stream))))
                .build();
    }

    private ProviderInfo toInfo(String id, ChatProperties.Provider provider) {
        return new ProviderInfo(id, provider.getName(), provider.getBaseUrl(), provider.getModel(),
                !isBlank(provider.getApiKey()));
    }

    private URI chatCompletionsUri(String baseUrl) {
        String normalized = baseUrl.trim().replaceAll("/+$", "");
        return URI.create(normalized + "/chat/completions");
    }

    private Map<String, Object> payload(ChatRequest request, String model, boolean stream) {
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
        body.put("stream", stream);
        return body;
    }

    private void readEventStream(InputStream input, StreamListener listener, AtomicBoolean cancelled) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            StringBuilder data = new StringBuilder();
            String line;
            while (!cancelled.get() && (line = reader.readLine()) != null) {
                if (line.isEmpty()) {
                    if (dispatchEvent(data, listener)) {
                        return;
                    }
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    if (!data.isEmpty()) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).stripLeading());
                }
            }
            if (!cancelled.get()) {
                dispatchEvent(data, listener);
            }
        }
    }

    private boolean dispatchEvent(StringBuilder data, StreamListener listener) throws IOException {
        if (data.isEmpty()) {
            return false;
        }
        String eventData = data.toString();
        if ("[DONE]".equals(eventData)) {
            return true;
        }
        JsonNode node = objectMapper.readTree(eventData);
        String content = node.path("choices").path(0).path("delta").path("content").asText();
        if (!isBlank(content)) {
            listener.onEvent(ChatStreamEvent.delta(content));
        }
        JsonNode usage = node.path("usage");
        if (!usage.isMissingNode() && !usage.isNull()) {
            listener.onEvent(ChatStreamEvent.usage(usage.toString()));
        }
        return false;
    }

    private ApiException upstreamFailure(PreparedChat prepared, String body) {
        return new ApiException(HttpStatus.BAD_GATEWAY,
                prepared.provider().getName() + " 请求失败：" + providerError(body));
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

    private void closeQuietly(InputStream input) {
        if (input == null) {
            return;
        }
        try {
            input.close();
        } catch (IOException ignored) {
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record PreparedChat(ChatProperties.Provider provider, String model) {
    }

    @FunctionalInterface
    public interface StreamListener {
        void onEvent(ChatStreamEvent event) throws IOException;
    }

    public record ChatStream(CompletableFuture<Void> completion, Runnable cancelAction) {
        public void cancel() {
            cancelAction.run();
        }
    }
}
