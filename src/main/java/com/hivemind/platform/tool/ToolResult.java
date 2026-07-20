package com.hivemind.platform.tool;

public record ToolResult<T>(boolean success, T payload, String errorMessage) {

    public static <T> ToolResult<T> success(T payload) {
        return new ToolResult<>(true, payload, null);
    }

    public static <T> ToolResult<T> failure(String errorMessage) {
        return new ToolResult<>(false, null, errorMessage);
    }
}
