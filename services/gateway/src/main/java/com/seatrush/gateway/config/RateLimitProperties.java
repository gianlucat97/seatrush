package com.seatrush.gateway.config;

import com.seatrush.gateway.domain.RateLimitScope;
import com.seatrush.gateway.domain.TokenBuckets.SubjectKind;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The token buckets of README section 22, two per scope of {@link RateLimitScope}.
 *
 * <p>Six pairs of numbers rather than one, because the three scopes cost different amounts and the
 * two subjects of a limit are not comparable. The per-user numbers are what one buyer may do; the
 * per-address numbers have to hold a whole office, a university or a mobile carrier behind one
 * address, so they are the looser pair by two orders of magnitude and they exist to stop a script,
 * not to shape a buyer.
 *
 * <p>The defaults are chosen against the sale of {@code README} section 18.1 rather than against a
 * round number. A buyer in a flash on-sale reaches for seats, is refused because somebody else got
 * them, and reaches again: the simulation of section 18.1.1 retries a conflict up to three times,
 * so a hold limit that refused the third retry would be refusing the system its own designed
 * behaviour. A burst of 20 holds with 5 a second back covers that with room to spare, and still
 * stops a client making a hundred attempts a second. Orders are the tightest because a buyer only
 * has one hold to turn into one order, and a second attempt is either a retry of a {@code 503},
 * which section 12.6 says is safe and which this leaves room for, or a mistake.
 *
 * <p>Explicit records per scope and not a map keyed by the enum, so that a scope added to {@link
 * RateLimitScope} and forgotten here fails to compile rather than throwing on the first request of
 * the next sale.
 *
 * @param enabled whether the limits are enforced at all
 * @param queueJoin taking a place in the waiting room
 * @param hold taking seats off the market
 * @param order turning a hold into an order
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.ratelimit")
public record RateLimitProperties(
    @DefaultValue("true") boolean enabled,
    @NotNull @Valid @DefaultValue Limits queueJoin,
    @NotNull @Valid @DefaultValue Limits hold,
    @NotNull @Valid @DefaultValue Limits order) {

  /**
   * The two buckets one request is weighed against.
   *
   * @param user what one signed-in buyer may do
   * @param ip what everybody behind one address may do between them
   */
  public record Limits(
      @NotNull @Valid @DefaultValue Bucket user, @NotNull @Valid @DefaultValue Bucket ip) {}

  /**
   * One bucket.
   *
   * @param burst how much may arrive at once, which is the size of the bucket
   * @param perSecond how many tokens are put back each second, which is the sustained rate
   */
  public record Bucket(
      @Min(1) @Max(1_000_000) @DefaultValue("20") int burst,
      @DecimalMin("0.01") @DefaultValue("5") double perSecond) {}

  /** The pair that applies to this scope. */
  public Limits limitsFor(RateLimitScope scope) {
    return switch (scope) {
      case QUEUE_JOIN -> queueJoin;
      case HOLD -> hold;
      case ORDER -> order;
    };
  }

  /** The one bucket that applies to this scope and this kind of subject. */
  public Bucket bucketFor(RateLimitScope scope, SubjectKind kind) {
    Limits limits = limitsFor(scope);
    return switch (kind) {
      case USER -> limits.user();
      case ADDRESS -> limits.ip();
    };
  }
}
