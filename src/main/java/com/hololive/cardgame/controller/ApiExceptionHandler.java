package com.hololive.cardgame.controller;

import com.hololive.cardgame.dto.ApiErrorResponse;
import com.hololive.cardgame.error.GameErrorCode;
import com.hololive.cardgame.error.GameRuleException;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(GameRuleException.class)
    /**
     * 處理遊戲規則例外，回傳標準化錯誤碼與細節。
     */
    public ResponseEntity<ApiErrorResponse> handleGameRule(GameRuleException ex, HttpServletRequest request) {
        return build(
            ex.getCode().httpStatus(),
            ex.getCode(),
            ex.getMessage(),
            ex.getDetails(),
            request
        );
    }

    @ExceptionHandler(ResponseStatusException.class)
    /**
     * 處理控制器主動拋出的 HTTP 狀態例外，映射為標準錯誤碼。
     */
    public ResponseEntity<ApiErrorResponse> handleResponseStatus(ResponseStatusException ex, HttpServletRequest request) {
        HttpStatus status = HttpStatus.resolve(ex.getStatusCode().value());
        if (status == null) {
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        }
        GameErrorCode fallbackCode = switch (status) {
            case UNAUTHORIZED -> GameErrorCode.UNAUTHORIZED;
            case FORBIDDEN -> GameErrorCode.FORBIDDEN;
            case BAD_REQUEST -> GameErrorCode.BAD_REQUEST;
            case NOT_FOUND -> GameErrorCode.NOT_FOUND;
            case CONFLICT -> GameErrorCode.CONFLICT;
            default -> GameErrorCode.INTERNAL_ERROR;
        };
        String message = ex.getReason() == null || ex.getReason().isBlank() ? status.getReasonPhrase() : ex.getReason();
        return build(status, fallbackCode, message, Collections.emptyMap(), request);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    /**
     * 處理參數不合法錯誤。
     */
    public ResponseEntity<ApiErrorResponse> handleIllegalArgument(IllegalArgumentException ex, HttpServletRequest request) {
        return build(HttpStatus.BAD_REQUEST, GameErrorCode.BAD_REQUEST, ex.getMessage(), Collections.emptyMap(), request);
    }

    @ExceptionHandler(IllegalStateException.class)
    /**
     * 處理狀態衝突或流程不允許錯誤。
     */
    public ResponseEntity<ApiErrorResponse> handleIllegalState(IllegalStateException ex, HttpServletRequest request) {
        return build(HttpStatus.CONFLICT, GameErrorCode.CONFLICT, ex.getMessage(), Collections.emptyMap(), request);
    }

    @ExceptionHandler(Exception.class)
    /**
     * 最終兜底：處理未預期例外。
     */
    public ResponseEntity<ApiErrorResponse> handleUnhandled(Exception ex, HttpServletRequest request) {
        return build(
            HttpStatus.INTERNAL_SERVER_ERROR,
            GameErrorCode.INTERNAL_ERROR,
            "系統發生未預期錯誤",
            Collections.emptyMap(),
            request
        );
    }

    /**
     * 建立統一 API 錯誤回應格式。
     */
    private ResponseEntity<ApiErrorResponse> build(
        HttpStatus status,
        GameErrorCode code,
        String message,
        Map<String, Object> details,
        HttpServletRequest request
    ) {
        ApiErrorResponse response = new ApiErrorResponse();
        response.setCode(code.name());
        response.setMessage(message == null ? "" : message);
        response.setDetails(details == null ? Collections.emptyMap() : details);
        response.setPath(request == null ? null : request.getRequestURI());
        response.setTimestamp(LocalDateTime.now());
        return ResponseEntity.status(status).body(response);
    }
}
