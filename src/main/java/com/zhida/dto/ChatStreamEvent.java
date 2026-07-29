package com.zhida.dto;

public record ChatStreamEvent(String type, String content, String provider, String model) {
    public static ChatStreamEvent meta(String provider, String model) {
        return new ChatStreamEvent("meta", "", provider, model);
    }

    public static ChatStreamEvent delta(String content) {
        return new ChatStreamEvent("delta", content, "", "");
    }

    public static ChatStreamEvent usage(String content) {
        return new ChatStreamEvent("usage", content, "", "");
    }

    public static ChatStreamEvent done() {
        return new ChatStreamEvent("done", "", "", "");
    }
}
