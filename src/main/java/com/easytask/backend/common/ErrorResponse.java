package com.easytask.backend.common;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ErrorResponse(
        int status,
        String code,
        String message,
        Map<String, String> fields
) {

    public static ErrorResponse of(int status, String code, String message) {
        return new ErrorResponse(status, code, message, Map.of());
    }
}
