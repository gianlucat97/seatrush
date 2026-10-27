package com.seatrush.queue.adapters.in.web;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.error.ProblemDetails;
import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.NotWaitingException;
import java.util.Map;
import org.springframework.http.ProblemDetail;
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

  /**
   * An event this service has not seen.
   *
   * <p>Which includes an event that exists but was created in the last second, because the catalog
   * is read on a schedule (see {@link com.seatrush.queue.application.SaleCalendar}). A client that
   * retries gets in; that is why this is a 404 and not a 400.
   */
  @ExceptionHandler(EventNotFoundException.class)
  public ProblemDetail unknownEvent(EventNotFoundException failure) {
    return ProblemDetails.of(ErrorCode.NOT_FOUND, "No event with id " + failure.eventId());
  }

  /** A draft, a sold out sale or one that is over: nothing worth waiting for. */
  @ExceptionHandler(EventNotOnSaleException.class)
  public ProblemDetail notOnSale(EventNotOnSaleException failure) {
    return ProblemDetails.of(
        ErrorCode.EVENT_NOT_ON_SALE,
        "Event %s is not selling".formatted(failure.eventId()),
        Map.of("eventStatus", failure.status().name()));
  }

  /** A place asked for by somebody who has none. */
  @ExceptionHandler(NotWaitingException.class)
  public ProblemDetail notWaiting(NotWaitingException failure) {
    return ProblemDetails.of(
        ErrorCode.NOT_FOUND, "You are not waiting for event " + failure.eventId());
  }

  /**
   * A path variable or a header that is not of the expected type, such as an id that is not a UUID.
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

  /** The same, for a constraint on a path variable or a header. */
  @ExceptionHandler(HandlerMethodValidationException.class)
  public ProblemDetail invalidParameter(HandlerMethodValidationException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, "The request is not valid");
  }

  /**
   * A rule the domain enforces itself.
   *
   * <p>A {@link NullPointerException} is deliberately not handled here: a missing header is
   * rejected by the framework before it reaches a controller, so a null that gets to the domain is
   * a bug and deserves a 500 and an alert rather than a 400.
   */
  @ExceptionHandler(IllegalArgumentException.class)
  public ProblemDetail invalidRequest(IllegalArgumentException failure) {
    return ProblemDetails.of(ErrorCode.VALIDATION_ERROR, failure.getMessage());
  }
}
