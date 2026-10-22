package com.seatrush.inventory.adapters.out.redis;

import com.seatrush.inventory.domain.SeatState;
import com.seatrush.inventory.domain.SeatStateStore;
import com.seatrush.inventory.domain.SeatStatus;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.RedisOperations;
import org.springframework.data.redis.core.SessionCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@link SeatStateStore} on Redis (README section 9.2).
 *
 * <p>Both operations run inside {@code MULTI}/{@code EXEC}, for the same reason in each direction.
 * A warm-up that was visible half written would offer seats the hash does not know about; a read
 * that took the version and the seat hash in two round trips could label a map with a version the
 * map is not at, and the client would then drop the delta that explains the difference.
 */
@Repository
public class RedisSeatStateStore implements SeatStateStore {

  /** Fields per {@code HSET}, so one warm-up is tens of commands and not tens of thousands. */
  static final int CHUNK_SIZE = 1_000;

  private final StringRedisTemplate redis;

  public RedisSeatStateStore(StringRedisTemplate redis) {
    this.redis = redis;
  }

  @Override
  public void warmUp(UUID eventId, Collection<String> seatIds, Set<String> soldSeatIds) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(seatIds, "seatIds must not be null");
    Objects.requireNonNull(soldSeatIds, "soldSeatIds must not be null");

    List<Map<String, String>> chunks = encode(seatIds, soldSeatIds);
    int available = seatIds.size() - countSoldOf(seatIds, soldSeatIds);
    String seatsKey = SeatKeys.seats(eventId);
    String versionKey = SeatKeys.version(eventId);
    String availableKey = SeatKeys.available(eventId);

    redis.execute(
        new SessionCallback<Object>() {
          @Override
          @SuppressWarnings("unchecked")
          public Object execute(RedisOperations operations) {
            operations.multi();
            // A full replacement, not a merge: a leftover seat of an older layout would stay
            // in the hash forever and be held by someone.
            operations.delete(List.of(seatsKey, versionKey, availableKey));
            for (Map<String, String> chunk : chunks) {
              operations.opsForHash().putAll(seatsKey, chunk);
            }
            // The first change of the sale will make it 1, so a snapshot of an untouched sale
            // is at version 0 and every later delta is strictly newer.
            operations.opsForValue().set(versionKey, "0");
            operations.opsForValue().set(availableKey, Integer.toString(available));
            return operations.exec();
          }
        });
  }

  @Override
  public Optional<SeatState> read(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    String seatsKey = SeatKeys.seats(eventId);
    String versionKey = SeatKeys.version(eventId);
    String availableKey = SeatKeys.available(eventId);

    List<Object> results =
        redis.execute(
            new SessionCallback<List<Object>>() {
              @Override
              @SuppressWarnings("unchecked")
              public List<Object> execute(RedisOperations operations) {
                operations.multi();
                operations.opsForValue().get(versionKey);
                operations.opsForValue().get(availableKey);
                operations.opsForHash().entries(seatsKey);
                return operations.exec();
              }
            });

    Map<Object, Object> stored = hashOf(results);
    if (stored.isEmpty()) {
      return Optional.empty();
    }
    Map<String, SeatStatus> statuses = new HashMap<>(stored.size());
    stored.forEach(
        (seatId, value) ->
            statuses.put(String.valueOf(seatId), SeatEncoding.statusOf(String.valueOf(value))));
    return Optional.of(
        new SeatState(eventId, numberAt(results, 0), (int) numberAt(results, 1), statuses));
  }

  @Override
  public boolean isWarm(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    return Boolean.TRUE.equals(redis.hasKey(SeatKeys.seats(eventId)));
  }

  @Override
  public void clear(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    redis.delete(
        List.of(SeatKeys.seats(eventId), SeatKeys.version(eventId), SeatKeys.available(eventId)));
  }

  private static List<Map<String, String>> encode(
      Collection<String> seatIds, Set<String> soldSeatIds) {
    List<Map<String, String>> chunks = new ArrayList<>();
    Map<String, String> chunk = new LinkedHashMap<>(CHUNK_SIZE);
    for (String seatId : seatIds) {
      chunk.put(seatId, soldSeatIds.contains(seatId) ? SeatEncoding.SOLD : SeatEncoding.AVAILABLE);
      if (chunk.size() == CHUNK_SIZE) {
        chunks.add(chunk);
        chunk = new LinkedHashMap<>(CHUNK_SIZE);
      }
    }
    if (!chunk.isEmpty()) {
      chunks.add(chunk);
    }
    return chunks;
  }

  /**
   * How many of the sold ids actually belong to this venue.
   *
   * <p>Counted against the seats rather than taken from the size of the set, so a sale row for a
   * seat the layout no longer has cannot push the available counter below the truth.
   */
  private static int countSoldOf(Collection<String> seatIds, Set<String> soldSeatIds) {
    return (int) seatIds.stream().filter(soldSeatIds::contains).count();
  }

  @SuppressWarnings("unchecked")
  private static Map<Object, Object> hashOf(List<Object> results) {
    if (results == null || results.size() < 3 || !(results.get(2) instanceof Map<?, ?> hash)) {
      return Map.of();
    }
    return (Map<Object, Object>) hash;
  }

  /** Reads one of the two counters, treating a missing key as zero. */
  private static long numberAt(List<Object> results, int index) {
    Object value = results == null || results.size() <= index ? null : results.get(index);
    return value == null ? 0L : Long.parseLong(String.valueOf(value));
  }
}
