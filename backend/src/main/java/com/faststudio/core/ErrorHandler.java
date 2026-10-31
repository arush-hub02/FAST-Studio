package com.faststudio.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.ErrorResponse;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.UUID;

@RestControllerAdvice
public class ErrorHandler {
    private static final Logger log = LoggerFactory.getLogger(ErrorHandler.class);

    @ExceptionHandler(ApiException.class)
    ProblemDetail api(ApiException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(e.status, e.getMessage());
        p.setProperty("code", e.code);
        if (e.details != null) p.setProperty("details", e.details);
        return p;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage()).findFirst().orElse("Invalid request");
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT, msg);
        p.setProperty("code", "VALIDATION");
        return p;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail malformed(HttpMessageNotReadableException e) {
        ProblemDetail p=ProblemDetail.forStatusAndDetail(HttpStatus.UNPROCESSABLE_CONTENT,"Invalid request. Check required fields, dates and value types.");
        p.setProperty("code","VALIDATION");
        return p;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ProblemDetail missing(NoResourceFoundException e) {
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "Not found");
        p.setProperty("code", "NOT_FOUND");
        return p;
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        if (e instanceof ErrorResponse er) {           // Spring's own 4xx (405, 406, 415, missing param…): keep their status
            ProblemDetail p = er.getBody();
            p.setProperty("code", "HTTP_" + er.getStatusCode().value());
            return p;
        }
        String ref = UUID.randomUUID().toString().substring(0, 8);
        log.error("Unhandled error ref={}", ref, e);
        ProblemDetail p = ProblemDetail.forStatusAndDetail(HttpStatus.INTERNAL_SERVER_ERROR,
                "Something went wrong on the server (reference " + ref + ").");
        p.setProperty("code", "INTERNAL");
        return p;
    }
}
