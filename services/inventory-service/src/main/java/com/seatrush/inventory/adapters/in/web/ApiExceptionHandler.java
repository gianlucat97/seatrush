package com.seatrush.inventory.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.error.ProblemDetails;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.EventNotOnSaleException;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.IllegalEventTransitionException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
 * never on an HTTP status or a message.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

  @ExceptionHandler(EventNotFoundException.class)
  public ProblemDetail unknownEvent(EventNotFoundException failure) {
    return ProblemDetails.of(ErrorCode.NOT_FOUND, "No event with id " + failure.eventId());
  }

  /**
   * A lifecycle transition the event cannot make.
   *
   * <p>Reported as {@code VALIDATION_ERROR}: section 12.6 has no code for a conflict on the admin
   * lifecycle, and inventing one would change the published vocabulary, which is an ADR and a
   * README change rather than a detail of this endpoint. The statuses travel as extra members, so
   * the caller can tell a race apart from a wrong call without parsing the message.
   */
  @ExceptionHandler(IllegalEventTransitionException.class)
  public ProblemDetail illegalTransition(IllegalEventTransitionException failure) {
    return ProblemDetails.of(
        ErrorCode.VALIDATION_ERROR,
        "Event %s is %s and cannot become %s"
            .formatted(failure.eventId(), failure.from(), failure.to()),
        Map.of("currentStatus", failure.from().name(), "requestedStatus", failure.to().name()));
  }

  @ExceptionHandler(HoldNotFoundException.class)
  public ProblemDetail unknownHold(HoldNotFoundException failure) {
    return ProblemDetails.of(ErrorCode.NOT_FOUND, "No hold with id " + failure.holdId());
  }

  @ExceptionHandler(HoldNotOwnedException.class)
  public ProblemDetail notTheOwner(HoldNotOwnedException failure) {
    return ProblemDetails.of(ErrorCode.FORBIDDEN, "This hold belongs to another user");
  }

  /**
   * A hold that is no longer active, which is two different answers.
   *
   * <p>A hold in checkout or already consumed is in use by an order, so the client is told it is
   * taken; one that was released or expired is simply gone, which is what 410 means. Both carry the
   * status, so a client does not have to guess which of the two it got.
   */
  @ExceptionHandler(HoldNoLongerActiveException.class)
  public ProblemDetail holdNoLongerActive(HoldNoLongerActiveException failure) {
    boolean inUse =
        failure.status() == HoldStatus.CHECKOUT || failure.status() == HoldStatus.CONSUMED;
    ErrorCode code = inUse ? ErrorCode.HOLD_ALREADY_USED : ErrorCode.HOLD_EXPIRED;
    String detail = inUse ? "This hold is being used by an order" : "This hold is no longer active";
    return ProblemDetails.of(code, detail, Map.of("holdStatus", failure.status().name()));
  }

  /** Seats of an event whose sale is not open cannot be held (README section 6.3). */
  @ExceptionHandler(EventNotOnSaleException.class)
  public ProblemDetail notOnSale(EventNotOnSaleException failure) {
    return ProblemDetails.of(
        ErrorCode.EVENT_NOT_ON_SALE,
        "Event %s is not on sale".formatted(failure.eventId()),
        Map.of("eventStatus", failure.status().name()));
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
   * A path variable or a query parameter that is not of the expected type, such as an event id that
   * is not a UUID.
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
   * A rule the domain enforces itself, such as a section id used twice or a venue above the
   * generator limit.
   *
   * <p>These are the same class of mistake as a failed annotation, so they get the same code: the
   * caller sent something the service will not build. A {@link NullPointerException} is
   * deliberately not handled here: the annotations above reject a missing field, so a null that
   * reaches the domain is a bug and deserves a 500 and an alert, not a 400.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidRequest(IllegalArgumentException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, failure.getMessage());
  }
}
