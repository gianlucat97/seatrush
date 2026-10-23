package com.seatrush.payment.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.error.ProblemDetails;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * The one place where a failure of this service's HTTP surface becomes a Problem Details document
 * (README sections 12.6 and 21).
 *
 * <p>Short, because the surface is short: one internal endpoint that an operator or a benchmark
 * script calls. Everything this service really does arrives over Kafka, where a failure is a reply
 * message or a record in the dead letter topic rather than a status code.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  /** A request that failed its own constraints, with the offending fields listed. */
  @ExceptionHandler(MethodArgumentNotValidException.class)
  public ProblemDetail invalidBody(MethodArgumentNotValidException failure) {
    List<FieldError> fieldErrors = failure.getBindingResult().getFieldErrors();
    Map<String, Object> fields = new LinkedHashMap<>();
    for (FieldError fieldError : fieldErrors) {
      fields.putIfAbsent(fieldError.getField(), fieldError.getDefaultMessage());
    }
    return ProblemDetails.of(
        ErrorCode.VALIDATION_ERROR, "The request is not valid", Map.of("fields", fields));
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail unreadableBody(HttpMessageNotReadableException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "The request body is not readable");
  }

  /**
   * A rule the domain enforces itself, such as a 99th percentile below the median.
   *
   * <p>These are the same class of mistake as a failed annotation and get the same code: the caller
   * sent settings the simulator will not take.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidRequest(IllegalArgumentException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, failure.getMessage());
  }
}
