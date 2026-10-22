package com.seatrush.inventory.adapters.out.redis;

import com.seatrush.inventory.domain.SeatEventRecord;
import com.seatrush.inventory.domain.SeatEventStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.RedisSystemException;
import org.springframework.data.redis.connection.stream.Consumer;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessagesSummary;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.connection.stream.StreamReadOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;

/**
 * The {@link SeatEventStream} on Redis Streams (README section 9.5).
 *
 * <p>One consumer group per event, created on first use together with the stream itself. The group
 * is what turns a stream into an outbox: an entry read by this relay is pending until it is
 * acknowledged, so a pod that dies after publishing but before acknowledging costs a duplicate and
 * never a lost event.
 */
@Repository
public class RedisSeatEventStream implements SeatEventStream {

  /** The one group every relay of this service reads through. */
  static final String GROUP = "inventory-stream-relay";

  private static final Logger log = LoggerFactory.getLogger(RedisSeatEventStream.class);

  private final StringRedisTemplate redis;
  private final String consumerName;

  public RedisSeatEventStream(StringRedisTemplate redis) {
    this.redis = redis;
    // One name per process: two pods must not share a consumer, or neither could tell which
    // entries the other is still working on.
    this.consumerName = GROUP + "-" + UUID.randomUUID();
  }

  @Override
  public List<SeatEventRecord> read(UUID eventId, int count) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    String key = SeatKeys.seatEvents(eventId);
    ensureGroup(key);

    List<MapRecord<String, Object, Object>> records =
        redis
            .opsForStream()
            .read(
                Consumer.from(GROUP, consumerName),
                StreamReadOptions.empty().count(count),
                StreamOffset.create(key, ReadOffset.lastConsumed()));
    return toSeatEvents(eventId, records);
  }

  @Override
  public List<SeatEventRecord> claimStale(UUID eventId, Duration idleFor, int count) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(idleFor, "idleFor must not be null");
    String key = SeatKeys.seatEvents(eventId);
    ensureGroup(key);

    RecordId[] stale = pendingIds(key, idleFor, count);
    if (stale.length == 0) {
      return List.of();
    }
    List<MapRecord<String, Object, Object>> claimed =
        redis.opsForStream().claim(key, GROUP, consumerName, idleFor, stale);
    if (!claimed.isEmpty()) {
      log.info(
          "claimed {} seat events of event {} from a consumer that stopped",
          claimed.size(),
          eventId);
    }
    return toSeatEvents(eventId, claimed);
  }

  @Override
  public void acknowledge(UUID eventId, List<String> recordIds) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(recordIds, "recordIds must not be null");
    if (recordIds.isEmpty()) {
      return;
    }
    redis
        .opsForStream()
        .acknowledge(SeatKeys.seatEvents(eventId), GROUP, recordIds.toArray(String[]::new));
  }

  @Override
  public long pendingCount(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    String key = SeatKeys.seatEvents(eventId);
    if (!Boolean.TRUE.equals(redis.hasKey(key))) {
      return 0L;
    }
    ensureGroup(key);
    PendingMessagesSummary summary = redis.opsForStream().pending(key, GROUP);
    return summary == null ? 0L : summary.getTotalPendingMessages();
  }

  /**
   * Creates the group if it is not there, and the stream with it.
   *
   * <p>From the beginning of the stream, so a group created after the first hold still relays the
   * events that are already in it. Redis has no idempotent form of this command, so an existing
   * group is recognised by the error it answers with.
   */
  private void ensureGroup(String key) {
    try {
      redis.opsForStream().createGroup(key, ReadOffset.from("0"), GROUP);
    } catch (RedisSystemException alreadyThere) {
      if (!isBusyGroup(alreadyThere)) {
        throw alreadyThere;
      }
    }
  }

  private static boolean isBusyGroup(RuntimeException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains("BUSYGROUP")) {
        return true;
      }
    }
    return false;
  }

  /**
   * The entries of the group that have been idle for at least {@code idleFor}.
   *
   * <p>Asked of Redis rather than filtered here, so the decision of what counts as abandoned is
   * made against the clock of the server every consumer shares.
   */
  private RecordId[] pendingIds(String key, Duration idleFor, int count) {
    return redis.opsForStream().pending(key, GROUP, Range.unbounded(), count, idleFor).stream()
        .map(PendingMessage::getId)
        .toArray(RecordId[]::new);
  }

  private static List<SeatEventRecord> toSeatEvents(
      UUID eventId, List<MapRecord<String, Object, Object>> records) {
    return records == null
        ? List.of()
        : records.stream().map(record -> toSeatEvent(eventId, record)).toList();
  }

  /**
   * One stream entry as the scripts wrote it.
   *
   * <p>Everything is a string in a stream, which is why the numbers and the instant are parsed
   * here: the Lua side has no types to lose, and this is the only place that has to agree with it.
   */
  private static SeatEventRecord toSeatEvent(
      UUID eventId, MapRecord<String, Object, Object> record) {
    Map<Object, Object> fields = record.getValue();
    String seats = string(fields, "seats");
    return new SeatEventRecord(
        recordId(record),
        eventId,
        string(fields, "type"),
        seats == null || seats.isBlank() ? List.of() : Arrays.asList(seats.split(",")),
        Long.parseLong(string(fields, "version")),
        UUID.fromString(string(fields, "holdId")),
        Instant.ofEpochMilli(Long.parseLong(string(fields, "occurredAt"))));
  }

  private static String recordId(MapRecord<String, Object, Object> record) {
    RecordId id = record.getId();
    return id == null ? null : id.getValue();
  }

  private static String string(Map<Object, Object> fields, String field) {
    Object value = fields.get(field);
    if (value == null) {
      throw new IllegalStateException("a seat event in the stream carries no " + field);
    }
    return String.valueOf(value);
  }
}
