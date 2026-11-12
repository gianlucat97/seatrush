package com.seatrush.order.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.error.ProblemDetails;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldExpiredException;
import com.seatrush.order.domain.HoldNotFoundException;
import com.seatrush.order.domain.HoldNotOwnedException;
import com.seatrush.order.domain.IdempotencyKeyReusedException;
import com.seatrush.order.domain.InventoryUnavailableException;
import com.seatrush.order.domain.OrderNotFoundException;
import com.seatrush.order.domain.OrderNotOwnedException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * The one place where a failure becomes a Problem Details document (README sections 12.6 and 21).
 *
 * <p>Every answer carries the stable {@code code} of section 12.6, so a client branches on that and
 * never on an HTTP status or a message. The refusals inventory raised during the freeze are
 * reported with the code inventory used, which is what makes the two services one API from the
 * outside.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

  @ExceptionHandler(OrderNotFoundException.class)
  public ProblemDetail unknownOrder(OrderNotFoundException failure) {
    return ProblemDetails.of(ErrorCode.NOT_FOUND, "No order with id " + failure.orderId());
  }

  @ExceptionHandler(OrderNotOwnedException.class)
  public ProblemDetail notTheOwner(OrderNotOwnedException failure) {
    return ProblemDetails.of(ErrorCode.FORBIDDEN, "This order belongs to another user");
  }

  @ExceptionHandler(HoldNotFoundException.class)
  public ProblemDetail unknownHold(HoldNotFoundException failure) {
    return ProblemDetails.of(ErrorCode.NOT_FOUND, "No hold with id " + failure.holdId());
  }

  @ExceptionHandler(HoldNotOwnedException.class)
  public ProblemDetail holdNotOwned(HoldNotOwnedException failure) {
    return ProblemDetails.of(ErrorCode.FORBIDDEN, "This hold belongs to another user");
  }

  /**
   * The hold ran out before the checkout started.
   *
   * <p>No order was created, so there is nothing to compensate and nothing to clean up. The user
   * picks seats again, which is why this is a 410 and not a 409: the thing they were holding is
   * gone rather than contended.
   */
  @ExceptionHandler(HoldExpiredException.class)
  public ProblemDetail holdExpired(HoldExpiredException failure) {
    return ProblemDetails.of(
        ErrorCode.HOLD_EXPIRED, "This hold is no longer usable, please select seats again");
  }

  @ExceptionHandler(HoldAlreadyUsedException.class)
  public ProblemDetail holdAlreadyUsed(HoldAlreadyUsedException failure) {
    return ProblemDetails.of(
        ErrorCode.HOLD_ALREADY_USED, "This hold is already being used by an order");
  }

  @ExceptionHandler(IdempotencyKeyReusedException.class)
  public ProblemDetail idempotencyKeyReused(IdempotencyKeyReusedException failure) {
    return ProblemDetails.of(
        ErrorCode.IDEMPOTENCY_KEY_REUSED,
        "This idempotency key was used for a different hold",
        Map.of("idempotencyKey", failure.idempotencyKey().toString()));
  }

  /**
   * Inventory could not answer.
   *
   * <p>Retryable with the same key, which is the whole point of telling it apart from a refusal:
   * nothing was created here, and the freeze is idempotent for the order that was already offered
   * to it. Logged at warn, because unlike every other answer here this one is the system failing
   * rather than a request being wrong.
   */
  @ExceptionHandler(InventoryUnavailableException.class)
  public ProblemDetail inventoryUnavailable(InventoryUnavailableException failure) {
    log.warn("a checkout could not reach inventory", failure);
    return ProblemDetails.of(
        ErrorCode.TEMPORARILY_UNAVAILABLE,
        "The checkout could not be started, please retry with the same Idempotency-Key");
  }

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

  /**
   * A path variable, header or query parameter that is not of the expected type, such as an {@code
   * Idempotency-Key} that is not a UUID.
   *
   * <p>Handled here rather than left to the framework, so that every error of this API carries the
   * stable code of section 12.6: a 400 without one would be the single answer a client cannot
   * branch on.
   */
  @ExceptionHandler(MethodArgumentTypeMismatchException.class)
  public ProblemDetail mistypedParameter(MethodArgumentTypeMismatchException failure) {
    return ProblemDetails.of(
        ErrorCode.VALIDATION_ERROR, "%s is not a valid value".formatted(failure.getName()));
  }

  /** A body that is not readable at all: broken JSON, a wrong type, a missing body. */
  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ProblemDetail unreadableBody(HttpMessageNotReadableException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "The request body is not readable");
  }

  /** The same, for a constraint on a path variable or a query parameter. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ProblemDetail invalidParameter(HandlerMethodValidationException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "The request is not valid");
  }

  /**
   * A rule the domain enforces itself, such as a page size that is not positive.
   *
   * <p>A {@link NullPointerException} is deliberately not handled: the annotations on the DTOs
   * reject a missing field, so a null that reaches the domain is a bug and deserves a 500 and an
   * alert, not a 400.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidRequest(IllegalArgumentException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, failure.getMessage());
  }
}
