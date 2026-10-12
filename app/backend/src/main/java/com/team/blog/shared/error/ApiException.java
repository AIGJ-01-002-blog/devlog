package com.team.blog.shared.error;

import java.util.List;

import org.springframework.http.HttpStatus;

/** 업무 규칙 위반을 공통 오류 형식으로 돌려주기 위한 예외. 상태 코드 원칙은 docs/42 §4. */
public class ApiException extends RuntimeException {
    private final HttpStatus status;
    private final String code;
    private final transient List<FieldErrorItem> errors;
    private final transient Object details;

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, List.of(), null);
    }

    public ApiException(HttpStatus status, String code, String message, List<FieldErrorItem> errors, Object details) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors == null ? List.of() : List.copyOf(errors);
        this.details = details;
    }

    public static ApiException badRequest(String code, String message) {
        return new ApiException(HttpStatus.BAD_REQUEST, code, message);
    }

    public static ApiException conflict(String code, String message) {
        return new ApiException(HttpStatus.CONFLICT, code, message);
    }

    public static ApiException conflict(String code, String message, Object details) {
        return new ApiException(HttpStatus.CONFLICT, code, message, List.of(), details);
    }

    public static ApiException validation(List<FieldErrorItem> errors) {
        return new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", "입력값을 확인해 주세요.", errors, null);
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public List<FieldErrorItem> errors() { return errors; }
    public Object details() { return details; }
}
