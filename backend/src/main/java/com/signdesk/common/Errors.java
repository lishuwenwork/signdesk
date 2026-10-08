package com.signdesk.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class Errors {
    private static final Logger LOG = LoggerFactory.getLogger(Errors.class);

    @ExceptionHandler(ApiException.class)
    ResponseEntity<?> user(ApiException e) {
        return ResponseEntity.status(e.status()).body(Map.of("message", e.getMessage()));
    }

    @ExceptionHandler({
        MethodArgumentNotValidException.class,
        HttpMessageNotReadableException.class,
        IllegalArgumentException.class
    })
    ResponseEntity<?> bad(Exception e) {
        return ResponseEntity.badRequest().body(Map.of("message", "输入格式或字段范围不正确，请检查表单"));
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> internal(Exception e) {
        // Deliberately omit exception messages, SQL parameters and stack traces: they can contain
        // credentials.
        LOG.error("Operation failed ({})", e.getClass().getSimpleName());
        return ResponseEntity.internalServerError()
                .body(Map.of("message", "服务处理失败，请检查服务状态或重试管理操作"));
    }
}
