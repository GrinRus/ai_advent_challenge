package com.aiadvent.backend.common.exception;

import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

  @ExceptionHandler(Exception.class)
  public ResponseEntity<ProblemDetail> handleUnexpectedException(Exception ex) {
    log.error("Unhandled exception", ex);
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.INTERNAL_SERVER_ERROR);
    problem.setTitle("Unexpected error");
    problem.setDetail("Произошла непредвиденная ошибка. Попробуйте повторить запрос позже.");
    return ResponseEntity.internalServerError().body(problem);
  }

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ProblemDetail> handleValidationErrors(Exception ex) {
    ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
    problem.setTitle("Validation failed");
    problem.setDetail(resolveValidationMessage(ex));
    return ResponseEntity.badRequest().body(problem);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ProblemDetail> handleResponseStatusException(ResponseStatusException ex) {
    return ResponseEntity.status(ex.getStatusCode()).body(ex.getBody());
  }

  private String resolveValidationMessage(Exception ex) {
    if (ex instanceof MethodArgumentNotValidException methodArgumentNotValidException) {
      return methodArgumentNotValidException
          .getBindingResult()
          .getFieldErrors()
          .stream()
          .findFirst()
          .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid request payload")
          .orElse("Invalid request payload");
    }
    if (ex instanceof BindException bindException) {
      return bindException
          .getBindingResult()
          .getAllErrors()
          .stream()
          .findFirst()
          .map(error -> error.getDefaultMessage() != null ? error.getDefaultMessage() : "Invalid request payload")
          .orElse("Invalid request payload");
    }
    return "Invalid request payload";
  }
}
