package com.seatrush.gateway.domain;

import java.time.Duration;

/**
 * What a bucket answered: whether this request may proceed, and if not, how long until one may.
 *
 * <p>The wait is part of the verdict rather than something the gateway estimates, because the
 * bucket is the only thing that knows how empty it is. It becomes the {@code Retry-After} header of
 * the refusal, which is the difference between a client that backs off correctly and one that
 * retries in a loop and makes the problem it was refused for worse.
 *
 * @param allowed whether the request may proceed
 * @param retryAfter how long until a token exists; {@link Duration#ZERO} on an allowed request
 */
public record RateLimitVerdict(boolean allowed, Duration retryAfter) {

  /** The answer for a request that found a token. */
  public static final RateLimitVerdict ALLOWED = new RateLimitVerdict(true, Duration.ZERO);

  public RateLimitVerdict {
    if (retryAfter == null) {
      throw new IllegalArgumentException("retryAfter must not be null");
    }
    if (retryAfter.isNegative()) {
      throw new IllegalArgumentException("retryAfter must not be negative, was " + retryAfter);
    }
    if (allowed && !retryAfter.isZero()) {
      throw new IllegalArgumentException(
          "an allowed request has nothing to wait for, was told " + retryAfter);
    }
  }

  /** The answer for a request that found the bucket empty. */
  public static RateLimitVerdict refused(Duration retryAfter) {
    return new RateLimitVerdict(false, retryAfter);
  }

  /**
   * The wait in whole seconds, which is the only unit {@code Retry-After} has.
   *
   * <p>Rounded up and never below one: a header saying zero would be an invitation to retry
   * immediately, which is what the refusal exists to prevent.
   */
  public long retryAfterSeconds() {
    long millis = retryAfter.toMillis();
    return Math.max(1, (millis + 999) / 1000);
  }

  /**
   * The stricter of two verdicts, which is how a user bucket and an IP bucket are combined.
   *
   * <p>Either bucket refusing is a refusal, and the wait is the longer of the two, because a client
   * told the shorter one would come back and be refused by the other.
   */
  public RateLimitVerdict and(RateLimitVerdict other) {
    if (allowed && other.allowed) {
      return ALLOWED;
    }
    Duration wait = retryAfter.compareTo(other.retryAfter) >= 0 ? retryAfter : other.retryAfter;
    return refused(wait);
  }
}
