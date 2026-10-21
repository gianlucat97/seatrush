package com.seatrush.common.error;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;

/**
 * The stable error vocabulary of the public API (README §12.6).
 *
 * <p>Every error the API returns is one of these codes. The code is part of the contract and a
 * client may branch on it, while the HTTP status, the title and the detail message are allowed to
 * be read by humans only. Adding a code is a contract change; renaming one is a breaking change.
 */
public enum ErrorCode {
  VALIDATION_ERROR(HttpStatus.BAD_REQUEST, "Invalid request"),
  UNAUTHENTICATED(HttpStatus.UNAUTHORIZED, "Authentication required"),
  ADMISSION_REQUIRED(HttpStatus.FORBIDDEN, "Admission required"),
  FORBIDDEN(HttpStatus.FORBIDDEN, "Forbidden"),
  NOT_FOUND(HttpStatus.NOT_FOUND, "Not found"),
  EVENT_NOT_ON_SALE(HttpStatus.CONFLICT, "Event is not on sale"),
  SEAT_UNAVAILABLE(HttpStatus.CONFLICT, "Seat unavailable"),
  HOLD_LIMIT_REACHED(HttpStatus.CONFLICT, "Hold limit reached"),
  HOLD_ALREADY_USED(HttpStatus.CONFLICT, "Hold already used"),
  HOLD_EXPIRED(HttpStatus.GONE, "Hold expired"),
  IDEMPOTENCY_KEY_REUSED(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency key reused"),
  RATE_LIMITED(HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded"),
  TEMPORARILY_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE, "Temporarily unavailable");

  private static final String TYPE_PREFIX = "urn:seatrush:error:";

  private final HttpStatus status;
  private final String title;
  private final URI type;

  ErrorCode(HttpStatus status, String title) {
    this.status = status;
    this.title = title;
    this.type = URI.create(TYPE_PREFIX + name().toLowerCase(Locale.ROOT).replace('_', '-'));
  }

  /** The HTTP status this code is always returned with. */
  public HttpStatus status() {
    return status;
  }

  /** Short human readable summary, stable per code and independent of the single occurrence. */
  public String title() {
    return title;
  }

  /** Stable URN identifying the problem type, used as the {@code type} member of the response. */
  public URI type() {
    return type;
  }

  /** Whether a client may retry the same request, with the same idempotency key, and succeed. */
  public boolean retryable() {
    return this == RATE_LIMITED || this == TEMPORARILY_UNAVAILABLE;
  }

  /**
   * Parses a code received from another service.
   *
   * @throws IllegalArgumentException if the value is not a known code
   */
  public static ErrorCode of(String value) {
    Objects.requireNonNull(value, "value must not be null");
    return ErrorCode.valueOf(value.trim().toUpperCase(Locale.ROOT));
  }
}
