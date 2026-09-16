package com.inditex.similarproducts.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    ResponseEntity<Void> handleNotFound() {
        return ResponseEntity.notFound().build();
    }

    @ExceptionHandler(UpstreamServiceException.class)
    ResponseEntity<Void> handleUpstreamFailure() {
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).build();
    }
}
