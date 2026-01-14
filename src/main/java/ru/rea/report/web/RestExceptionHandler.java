package ru.rea.report.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import ru.rea.report.exception.BadTemplateException;
import ru.rea.report.exception.TemplateProcessingException;

import java.util.Map;

@RestControllerAdvice
public class RestExceptionHandler {

    @ExceptionHandler(BadTemplateException.class)
    public ResponseEntity<Map<String, Object>> badTemplate(BadTemplateException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("error", "BAD_TEMPLATE", "message", e.getMessage()));
    }

    @ExceptionHandler(TemplateProcessingException.class)
    public ResponseEntity<Map<String, Object>> processing(TemplateProcessingException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "PROCESSING_ERROR", "message", e.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> other(Exception e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("error", "UNEXPECTED_ERROR", "message", e.getMessage()));
    }
}
