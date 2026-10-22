package com.seatrush.inventory.adapters.out.redis;

import com.seatrush.inventory.domain.HoldDirectory;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@link HoldDirectory} on Redis: one small key per hold, pointing at its event.
 *
 * <p>This is the only key of a hold that is not under the hash tag of its event, which is the whole
 * point: {@code GET /api/holds/{holdId}} knows nothing but the id. On a Redis Cluster it therefore
 * lives on its own slot and cannot take part in the atomic script, so it is written right after the
 * hold and kept exactly as long.
 */
@Repository
public class RedisHoldDirectory implements HoldDirectory {

  /** The same retention as the hold hash, so the two disappear together. */
  static final Duration RETENTION = Duration.ofSeconds(RedisSeatHoldStore.HOLD_KEY_TTL_SECONDS);

  private final StringRedisTemplate redis;

  public RedisHoldDirectory(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void remember(UUID holdId, UUID eventId) {
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(eventId, "eventId must not be null");
    redis.opsForValue().set(key(holdId), eventId.toString(), RETENTION);
  }

  @Override
  public Optional<UUID> eventOf(UUID holdId) {
    Objects.requireNonNull(holdId, "holdId must not be null");
    return Optional.ofNullable(redis.opsForValue().get(key(holdId))).map(UUID::fromString);
  }

  static String key(UUID holdId) {
    return "hold-event:" + holdId;
  }
}
