package com.team.blog.shared.error;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String BAD_REQUEST = "BAD_REQUEST";

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ErrorResponse> handleApi(ApiException e) {
        return ResponseEntity.status(e.status())
                .body(new ErrorResponse(e.code(), e.getMessage(), e.errors(), e.details()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
        List<FieldErrorItem> errors = e.getBindingResult().getFieldErrors().stream()
                .map(f -> new FieldErrorItem(f.getField(), "INVALID", f.getDefaultMessage()))
                .toList();
        return ResponseEntity.badRequest()
                .body(new ErrorResponse("VALIDATION_FAILED", "입력값을 확인해 주세요.", errors, null));
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MissingRequestHeaderException.class, HttpMediaTypeNotSupportedException.class})
    ResponseEntity<ErrorResponse> handleBadRequest(Exception e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(BAD_REQUEST, "요청 형식이 올바르지 않아요."));
    }

    /**
     * 보안 방화벽이 막은 요청(헤더 값에 줄바꿈·제어 문자 등)은 보낸 쪽 문제라 400이다. 서버 오류 로그를 남기지 않고,
     * 예외 메시지에 헤더 값(토큰일 수 있다)이 들어 있으므로 기록하지 않는다.
     */
    @ExceptionHandler(RequestRejectedException.class)
    ResponseEntity<ErrorResponse> handleRejected(RequestRejectedException e) {
        return ResponseEntity.badRequest().body(ErrorResponse.of(BAD_REQUEST,
                "요청 헤더에 쓸 수 없는 문자(줄바꿈 등)가 있어요. 토큰을 쓰셨다면 다시 붙여 넣어 주세요."));
    }

    /** 경로 변수 타입 오류(예: 글 번호 자리에 문자)는 없는 대상과 같은 404 (docs/40 §3 ②). */
    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("NOT_FOUND", NotFoundException.MESSAGE));
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<ErrorResponse> handleNoResource(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse.of("NOT_FOUND", NotFoundException.MESSAGE));
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ErrorResponse> handleMethod(HttpRequestMethodNotSupportedException e) {
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).body(ErrorResponse.of("METHOD_NOT_ALLOWED", "지원하지 않는 요청이에요."));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ErrorResponse> handleUnknown(Exception e) {
        // 스프링이 상태를 정해 둔 요청 쪽 오류(406·413 등)는 서버 고장이 아니다. 그 상태 그대로 돌려주고 오류 로그를 남기지 않는다.
        if (e instanceof org.springframework.web.ErrorResponse framework && framework.getStatusCode().is4xxClientError()) {
            return clientError(framework);
        }
        // 받을 쪽이 이미 연결을 끊었다. 쓸 곳이 없으니 아무것도 하지 않는다.
        if (e instanceof AsyncRequestNotUsableException) return null;
        log.error("처리하지 못한 오류", e);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(ErrorResponse.of("INTERNAL_ERROR", "잠시 후 다시 시도해 주세요."));
    }

    private static ResponseEntity<ErrorResponse> clientError(org.springframework.web.ErrorResponse framework) {
        HttpStatusCode status = framework.getStatusCode();
        // 스프링이 붙여 둔 헤더(406의 Accept, 405의 Allow 등)는 그대로 전한다
        ResponseEntity.BodyBuilder response = ResponseEntity.status(status).headers(framework.getHeaders());
        // 406은 요청이 받겠다는 형식으로 오류 본문을 쓸 수 없으므로 상태만 보낸다
        if (status.value() == HttpStatus.NOT_ACCEPTABLE.value()) return response.build();
        HttpStatus known = HttpStatus.resolve(status.value());
        return response
                .body(ErrorResponse.of(known == null ? BAD_REQUEST : known.name(), "요청 형식이 올바르지 않아요."));
    }
}
