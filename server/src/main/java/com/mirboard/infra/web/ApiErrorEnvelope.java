package com.mirboard.infra.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.Map;

public record ApiErrorEnvelope(ApiError error) {

    public static ApiErrorEnvelope of(String code, String message) {
        return new ApiErrorEnvelope(new ApiError(code, message, null));
    }

    public static ApiErrorEnvelope of(String code, String message, Map<String, Object> details) {
        return new ApiErrorEnvelope(new ApiError(code, message, details));
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ApiError(String code, String message, Map<String, Object> details) {
    }
}
