package com.moyuan.buysense.web;

import com.moyuan.buysense.retail.RetailDataGateway;
import com.moyuan.buysense.run.RunService;
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
                "message", error.getMessage()));
    }

    @ExceptionHandler(RunService.RunContractException.class)
    ResponseEntity<Map<String, Object>> runContract(RunService.RunContractException error) {
        HttpStatus status = error.code().equals("idempotency_key_reused")
                ? HttpStatus.CONFLICT
                : HttpStatus.UNPROCESSABLE_ENTITY;
        return ResponseEntity.status(status).body(Map.of(
                "error", error.code(),
                "message", error.getMessage()));
    }

    @ExceptionHandler(RetailDataGateway.ProviderException.class)
    ResponseEntity<Map<String, Object>> provider(RetailDataGateway.ProviderException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", error.code(),
                "message", "retail data provider unavailable"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalidArgument(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_request",
                "message", error.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "invalid_request",
                "message", "message must be present and no longer than 4000 characters"));
    }
}