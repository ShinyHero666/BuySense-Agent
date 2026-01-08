package com.buysense.web;

import com.buysense.retail.RetailDataGateway;
import com.buysense.retail.ReviewEvidenceGateway;
import com.buysense.run.RunService;
import com.buysense.sar.data.JavaDataPlaneValidationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
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
                "message", "resource not found"));
    }

    @ExceptionHandler(SessionIdentity.CrossSiteRequestException.class)
    ResponseEntity<Map<String, Object>> crossSite() {
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                "error", "cross_site_request_rejected",
                "message", "cross-site state-changing request was rejected"));
    }

    @ExceptionHandler(RunService.RunCapacityException.class)
    ResponseEntity<Map<String, Object>> runCapacity(RunService.RunCapacityException error) {
        HttpStatus status = error.overloaded()
                ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.TOO_MANY_REQUESTS;
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "1");
        return new ResponseEntity<>(Map.of(
                "error", error.code(),
                "message", error.getMessage()), headers, status);
    }

    @ExceptionHandler(RunService.RunContractException.class)
    ResponseEntity<Map<String, Object>> runContract(RunService.RunContractException error) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", error.code(),
                "message", error.getMessage()));
    }

    @ExceptionHandler(RetailDataGateway.ProviderException.class)
    ResponseEntity<Map<String, Object>> provider(RetailDataGateway.ProviderException error) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", error.code(),
                "message", "retail data provider unavailable"));
    }

    @ExceptionHandler(ReviewEvidenceGateway.ProviderException.class)
    ResponseEntity<Map<String, Object>> reviewProvider(
            ReviewEvidenceGateway.ProviderException error
    ) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", error.code(),
                "message", "review evidence provider unavailable"));
    }

    @ExceptionHandler(JavaDataPlaneValidationException.class)
    ResponseEntity<Map<String, Object>> dataPlaneValidation(
            JavaDataPlaneValidationException error
    ) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_error",
                "field", error.field(),
                "message", error.getMessage()));
    }

    @ExceptionHandler(AgentController.RequestValidationException.class)
    ResponseEntity<Map<String, Object>> requestValidation(
            AgentController.RequestValidationException error
    ) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_error",
                "field", error.field(),
                "message", error.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<Map<String, Object>> unreadable(HttpMessageNotReadableException error) {
        Throwable cause = error.getMostSpecificCause();
        if (cause instanceof AgentController.RequestValidationException validation) {
            return requestValidation(validation);
        }
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_error",
                "field", "body",
                "message", "request body does not match the API contract"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, Object>> invalidArgument(IllegalArgumentException error) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "validation_error",
                "field", "request",
                "message", error.getMessage()));
    }
}
