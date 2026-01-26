package com.moyuan.buysense.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;
import java.util.NoSuchElementException;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(NoSuchElementException.class)
    ResponseEntity<Map<String, Object>> notFound(NoSuchElementException error) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                "error", "not_found",
                "message", error.getMessage()
        ));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_request",
                "message", "message must be present and no longer than 4000 characters"
        ));
    }
}
