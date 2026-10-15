package com.radhikafarms.milkweb.ledger;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class LedgerExceptionHandler {

    @ExceptionHandler(LedgerConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(LedgerConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(LedgerNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(LedgerNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(LedgerValidationException.class)
    public ResponseEntity<Map<String, String>> handleValidation(LedgerValidationException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<Map<String, String>> handleMissingHeader(MissingRequestHeaderException ex) {
        return ResponseEntity.badRequest().body(Map.of("error", ex.getHeaderName() + " header is required"));
    }
}
