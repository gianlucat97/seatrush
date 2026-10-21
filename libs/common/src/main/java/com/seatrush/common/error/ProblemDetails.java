package com.seatrush.common.error;

import java.util.Map;
import java.util.Objects;
import org.springframework.http.ProblemDetail;

/**
 * Builds the RFC 9457 responses of every service.
 *
 * <p>All errors leave the API in this shape, so a client parses one envelope instead of one per
 * service:
 *
 * <pre>{@code
 * {
 *   "type": "urn:seatrush:error:seat-unavailable",
 *   "title": "Seat unavailable",
 *   "status": 409,
 *   "detail": "2 of the requested seats are no longer free",
 *   "code": "SEAT_UNAVAILABLE",
 *   "conflictingSeatIds": ["A-12", "A-13"]
 * }
 * }</pre>
 *
 * <p>The {@code code} member is the contract: {@link ErrorCode} decides the status and the title,
 * so the same failure cannot be reported as 409 by one service and 400 by another. Extra members
 * such as {@code conflictingSeatIds} carry what the client needs to recover.
 *
 * <p>Detail messages describe the request, never the internals: no stack traces, no SQL, no Redis
 * keys, no other user's data. Diagnostics belong in logs and traces, correlated through the trace
 * id.
 */
public final class ProblemDetails {

  /** Name of the member that carries the stable error code. */
  public static final String CODE_PROPERTY = "code";

  private ProblemDetails() {}

  /** Problem with the default title of the code and no detail. */
  public static ProblemDetail of(ErrorCode code) {
    return of(code, null);
  }

  /** Problem with a human readable detail describing this occurrence. */
  public static ProblemDetail of(ErrorCode code, String detail) {
    return of(code, detail, Map.of());
  }

  /**
   * Problem with extra top level members, for example the conflicting seat ids of a failed hold.
   *
   * @throws IllegalArgumentException if a property would overwrite the {@code code} member
   */
  public static ProblemDetail of(ErrorCode code, String detail, Map<String, Object> properties) {
    Objects.requireNonNull(code, "code must not be null");
    Objects.requireNonNull(properties, "properties must not be null");
    if (properties.containsKey(CODE_PROPERTY)) {
      throw new IllegalArgumentException(
          "the "
              + CODE_PROPERTY
              + " member is derived from the ErrorCode and cannot be overridden");
    }

    ProblemDetail problem = ProblemDetail.forStatus(code.status());
    problem.setType(code.type());
    problem.setTitle(code.title());
    problem.setDetail(detail);
    problem.setProperty(CODE_PROPERTY, code.name());
    properties.forEach(problem::setProperty);
    return problem;
  }
}
