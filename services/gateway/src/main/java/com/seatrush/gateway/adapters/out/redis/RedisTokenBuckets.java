package com.seatrush.gateway.adapters.out.redis;

import com.seatrush.gateway.config.RateLimitProperties;
import com.seatrush.gateway.config.RateLimitProperties.Bucket;
import com.seatrush.gateway.domain.RateLimitScope;
import com.seatrush.gateway.domain.RateLimitVerdict;
import com.seatrush.gateway.domain.TokenBuckets;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * The buckets of README section 13, held in Redis and spent one Lua call at a time.
 *
 * <p>One call per bucket, so a limited request costs two: the caller's own bucket and the bucket of
 * the address they came from. Two calls rather than one script over two keys, because the two keys
 * belong to different callers and would not share a slot on a Redis Cluster, and because a refusal
 * by either is a refusal: there is nothing a single script would decide that the stricter of the
 * two verdicts does not.
 *
 * <p><strong>A Redis that cannot be reached lets the request through, and says so.</strong> That
 * looks like the wrong default for a protection and it is the right one here, for a reason specific
 * to this system: all three limited requests need Redis further down anyway. A queue join is
 * written to it, a hold is a Lua script on it, and an order freezes a hold that lives in it. A
 * gateway that refused purchases because the limiter could not reach Redis would be turning a store
 * outage into a second outage of its own, on the only path that could still have answered a read.
 * So a failure here is counted rather than enforced, and {@code seatrush_ratelimit_degraded_total}
 * is what tells an operator the limits are not currently being applied.
 */
@Component
public class RedisTokenBuckets implements TokenBuckets {

  private static final String KEY_PREFIX = "ratelimit:";

  private static final Logger log = LoggerFactory.getLogger(RedisTokenBuckets.class);

  private final ReactiveStringRedisTemplate redis;
  private final RateLimitScripts scripts;
  private final RateLimitProperties properties;
  private final Clock clock;
  private final MeterRegistry meters;

  public RedisTokenBuckets(
      ReactiveStringRedisTemplate redis,
      RateLimitScripts scripts,
      RateLimitProperties properties,
      Clock clock,
      MeterRegistry meters) {
    this.redis = redis;
    this.scripts = scripts;
    this.properties = properties;
    this.clock = clock;
    this.meters = meters;
  }

  @Override
  public CompletionStage<RateLimitVerdict> take(
      RateLimitScope scope, String subject, SubjectKind kind) {
    Bucket bucket = properties.bucketFor(scope, kind);
    String key = KEY_PREFIX + scope.label() + ":" + kind.prefix() + ":" + subject;

    return redis
        .execute(
            scripts.tokenBucket(),
            List.of(key),
            List.of(
                String.valueOf(bucket.burst()),
                String.valueOf(bucket.perSecond()),
                String.valueOf(clock.millis())))
        .next()
        .map(reply -> verdictOf(reply, scope, kind))
        .onErrorResume(unreachable -> Mono.just(degraded(scope, kind, unreachable)))
        .defaultIfEmpty(RateLimitVerdict.ALLOWED)
        .toFuture();
  }

  /**
   * The two integers the script answers, in the order its header documents.
   *
   * <p>A refusal is counted here rather than in the filter, because this is the only place that
   * knows which of the two buckets was the empty one. {@code scope} says what was being protected
   * and {@code subject} says whether it was one buyer or everybody behind one address, and those
   * are two different findings: the first is a client misbehaving, the second is usually a shared
   * address whose limits need raising.
   */
  @SuppressWarnings("rawtypes")
  private RateLimitVerdict verdictOf(List reply, RateLimitScope scope, SubjectKind kind) {
    long allowed = ((Number) reply.get(0)).longValue();
    if (allowed == 1L) {
      return RateLimitVerdict.ALLOWED;
    }
    counter("seatrush_ratelimit_refusals_total", "Requests refused by a token bucket", scope, kind)
        .increment();
    long retryAfterMillis = ((Number) reply.get(1)).longValue();
    return RateLimitVerdict.refused(Duration.ofMillis(retryAfterMillis));
  }

  /**
   * The answer when Redis did not give one: let it through, and count it.
   *
   * <p>Logged at warn and not at error, because the request it let through is one the services
   * behind the gateway are about to refuse for the same underlying reason.
   */
  private RateLimitVerdict degraded(RateLimitScope scope, SubjectKind kind, Throwable cause) {
    counter(
            "seatrush_ratelimit_degraded_total",
            "Rate limit checks that could not be made because Redis did not answer",
            scope,
            kind)
        .increment();
    log.warn(
        "the {} bucket of a {} could not be read, so the request was not limited: {}",
        subjectOf(kind),
        scope.label(),
        cause.getMessage());
    return RateLimitVerdict.ALLOWED;
  }

  private Counter counter(String name, String description, RateLimitScope scope, SubjectKind kind) {
    return Counter.builder(name)
        .description(description)
        .tag("scope", scope.label())
        .tag("subject", subjectOf(kind))
        .register(meters);
  }

  private static String subjectOf(SubjectKind kind) {
    return kind.name().toLowerCase(Locale.ROOT);
  }
}
