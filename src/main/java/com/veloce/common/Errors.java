package com.veloce.common;

import java.util.*;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.server.ResponseStatusException;

@RestControllerAdvice
public class Errors {
    public static Map<String,Object> body(Problem e, String requestId) {
        return Map.of("code", e.code, "message", e.getMessage(), "fieldErrors", e.issues,
            "retryable", e.status == 429 || e.status >= 500, "requestId", requestId);
    }
    @ExceptionHandler(Problem.class)
    ResponseEntity<?> problem(Problem e, HttpServletRequest request) {
        return ResponseEntity.status(e.status).body(body(e, requestId(request)));
    }
    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> limit(HttpServletRequest request) {
        return problem(new Problem(413, "FILE_TOO_LARGE", "Choose a roadmap under 10 MB."), request);
    }
    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class,
        org.springframework.web.bind.MissingServletRequestParameterException.class,
        org.springframework.web.multipart.support.MissingServletRequestPartException.class})
    ResponseEntity<?> invalid(HttpServletRequest request) {
        return problem(Problem.invalid("Check the request fields and try again."), request);
    }
    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
        if (e instanceof ResponseStatusException status) {
            return problem(new Problem(status.getStatusCode().value(), "REQUEST_ERROR", "This request could not be completed."), request);
        }
        String id = requestId(request);
        LoggerFactory.getLogger(Errors.class).error("Request {} failed ({})", id, e.getClass().getSimpleName());
        return ResponseEntity.internalServerError().body(body(new Problem(500, "SERVER_ERROR",
            "Veloce could not finish this request. Try again."), id));
    }
    private String requestId(HttpServletRequest request) {
        Object id = request.getAttribute("requestId");
        return id == null ? UUID.randomUUID().toString() : id.toString();
    }
}
