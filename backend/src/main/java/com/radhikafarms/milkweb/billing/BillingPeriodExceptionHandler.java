package com.radhikafarms.milkweb.billing;

import com.radhikafarms.milkweb.billing.exception.BillingPeriodConflictException;
import com.radhikafarms.milkweb.billing.exception.BillConflictException;
import com.radhikafarms.milkweb.billing.exception.BillNotFoundException;
import com.radhikafarms.milkweb.billing.exception.PeriodClosedException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class BillingPeriodExceptionHandler {

    @ExceptionHandler(BillNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleBillNotFound(BillNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(BillConflictException.class)
    public ResponseEntity<Map<String, String>> handleBillConflict(BillConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(BillingPeriodConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(BillingPeriodConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(PeriodClosedException.class)
    public ResponseEntity<Map<String, String>> handlePeriodClosed(PeriodClosedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
