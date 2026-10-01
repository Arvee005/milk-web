package com.radhikafarms.milkweb.dailyrecord;

import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordConflictException;
import com.radhikafarms.milkweb.dailyrecord.exception.DailyRecordNotFoundException;
import com.radhikafarms.milkweb.dailyrecord.exception.DuplicateDailyRecordException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class DailyRecordExceptionHandler {

    @ExceptionHandler(DailyRecordNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(DailyRecordNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DuplicateDailyRecordException.class)
    public ResponseEntity<Map<String, String>> handleDuplicate(DuplicateDailyRecordException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }

    @ExceptionHandler(DailyRecordConflictException.class)
    public ResponseEntity<Map<String, String>> handleConflict(DailyRecordConflictException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("error", ex.getMessage()));
    }
}
