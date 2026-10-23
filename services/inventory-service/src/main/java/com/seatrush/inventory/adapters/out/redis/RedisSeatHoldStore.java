package com.seatrush.inventory.adapters.out.redis;

import com.seatrush.inventory.domain.FreezeResult;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldRequest;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.ReleaseResult;
import com.seatrush.inventory.domain.SeatHoldStore;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link SeatHoldStore} on Redis: three Lua scripts and the two reads around them.
 *
 * <p>Every script answers with a JSON object, which is what lets one call carry an outcome, the new
 * version and the seats that caused a conflict. The mapper here is private to the adapter: the
 * protocol between this class and the scripts is internal, and must not change because the public
 * API changed how it writes JSON.
 *
 * <p>The key list of a script is always complete and always built from one event, because Redis
 * Cluster routes a script by the keys it declares. That is also why a release takes the hold rather
 * than its id: the key of the user is part of what the script touches, and it is only knowable from
 * the hold itself.
 */
@Repository
public class RedisSeatHoldStore implements SeatHoldStore {

  /**
   * How many entries the seat event stream of one event keeps, trimmed approximately.
   *
   * <p>The stream is a buffer for the relay and not a log: at 100,000 entries the relay can be down
   * for a long while without an event being lost, and the memory it costs stays bounded per event.
   */
  static final String STREAM_MAXLEN = "100000";

  /** How long the stored answer of an idempotency key lives (README section 9.4). */
  static final long IDEMPOTENCY_TTL_SECONDS = 24 * 60 * 60L;

  /**
   * How long the hold hash itself lives.
   *
   * <p>Far longer than the hold: a user who comes back after the sweeper took the seats reads that
   * the hold expired instead of a 404, and the checkout SAGA can still look up a hold it froze.
   */
  static final long HOLD_KEY_TTL_SECONDS = 24 * 60 * 60L;

  private static final JsonMapper REPLIES =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final StringRedisTemplate redis;
  private final HoldScripts scripts;

  public RedisSeatHoldStore(StringRedisTemplate redis, HoldScripts scripts) {
    this.redis = redis;
    this.scripts = scripts;
  }

  @Override
  public HoldResult hold(HoldRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    UUID eventId = request.eventId();
    UUID holdId = request.holdId();

    List<String> keys =
        List.of(
            SeatKeys.seats(eventId),
            SeatKeys.version(eventId),
            SeatKeys.available(eventId),
            SeatKeys.holdExpiry(eventId),
            SeatKeys.userHold(eventId, request.userId()),
            SeatKeys.idempotency(eventId, request.userId(), request.idempotencyKey()),
            SeatKeys.hold(eventId, holdId),
            SeatKeys.seatEvents(eventId));

    List<String> args =
        new ArrayList<>(
            List.of(
                holdId.toString(),
                request.userId().toString(),
                Long.toString(request.expiresAt().toEpochMilli()),
                Long.toString(request.now().toEpochMilli()),
                Long.toString(IDEMPOTENCY_TTL_SECONDS),
                Long.toString(HOLD_KEY_TTL_SECONDS),
                Long.toString(userHoldTtlMillis(request)),
                STREAM_MAXLEN,
                eventId.toString(),
                request.fingerprint()));
    args.addAll(request.seatIds());

    ScriptReply reply = run(scripts.hold(), keys, args);
    return switch (reply.status()) {
      // The id comes from the reply and not from the request: a replay of an idempotent request
      // answers with the hold that already exists, which is a different id from the one this
      // attempt generated and threw away.
      case "OK" ->
          new HoldResult.Held(
              new Hold(
                  UUID.fromString(reply.requireHoldId()),
                  eventId,
                  request.userId(),
                  reply.seatIdsOrElse(request.seatIds()),
                  Instant.ofEpochMilli(reply.requireExpiresAt()),
                  HoldStatus.ACTIVE),
              reply.requireVersion());
      case "CONFLICT" -> new HoldResult.Conflict(reply.requireConflictingSeatIds());
      case "UNKNOWN_SEAT" -> new HoldResult.UnknownSeats(reply.requireUnknownSeatIds());
      case "HOLD_LIMIT" -> new HoldResult.LimitReached(UUID.fromString(reply.requireHoldId()));
      case "IDEMPOTENCY_KEY_REUSED" -> new HoldResult.IdempotencyKeyReused();
      default -> throw new IllegalStateException("hold.lua answered " + reply.status());
    };
  }

  @Override
  public ReleaseResult release(
      Hold hold, Optional<UUID> callerId, Set<HoldStatus> allowedStatuses, Instant now) {
    Objects.requireNonNull(hold, "hold must not be null");
    Objects.requireNonNull(now, "now must not be null");
    if (allowedStatuses.isEmpty()) {
      throw new IllegalArgumentException("a release has to allow at least one status");
    }

    List<String> args =
        List.of(
            hold.id().toString(),
            Long.toString(now.toEpochMilli()),
            STREAM_MAXLEN,
            hold.eventId().toString(),
            callerId.map(UUID::toString).orElse(""),
            String.join(",", allowedStatuses.stream().map(HoldStatus::name).sorted().toList()));

    return releaseResultOf(run(scripts.release(), holdKeys(hold), args));
  }

  @Override
  public ReleaseResult expire(Hold hold, Instant now) {
    Objects.requireNonNull(hold, "hold must not be null");
    Objects.requireNonNull(now, "now must not be null");

    List<String> args =
        List.of(
            hold.id().toString(),
            Long.toString(now.toEpochMilli()),
            STREAM_MAXLEN,
            hold.eventId().toString());

    return releaseResultOf(run(scripts.expire(), holdKeys(hold), args));
  }

  @Override
  public FreezeResult freeze(Hold hold, UUID orderId, Optional<UUID> callerId, Instant now) {
    Objects.requireNonNull(hold, "hold must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(now, "now must not be null");

    // Only the two keys the freeze touches. The seats, the version and the stream are left out
    // because a freeze changes none of them, and a key a script declares but never reads would
    // claim a lock on a cluster for no reason.
    List<String> keys =
        List.of(SeatKeys.holdExpiry(hold.eventId()), SeatKeys.hold(hold.eventId(), hold.id()));

    List<String> args =
        List.of(
            hold.id().toString(),
            orderId.toString(),
            callerId.map(UUID::toString).orElse(""),
            Long.toString(now.toEpochMilli()));

    ScriptReply reply = run(scripts.freeze(), keys, args);
    return switch (reply.status()) {
      case "OK" ->
          new FreezeResult.Frozen(
              new Hold(
                  hold.id(),
                  hold.eventId(),
                  hold.userId(),
                  reply.seatIdsOrElse(hold.seatIds()),
                  Instant.ofEpochMilli(reply.requireExpiresAt()),
                  HoldStatus.CHECKOUT),
              UUID.fromString(reply.requireOrderId()));
      case "ALREADY_FROZEN" ->
          new FreezeResult.AlreadyFrozen(UUID.fromString(reply.requireOrderId()));
      case "NOT_FOUND" -> new FreezeResult.NotFound();
      case "FORBIDDEN" -> new FreezeResult.NotTheOwner();
      case "EXPIRED" -> new FreezeResult.Expired(Instant.ofEpochMilli(reply.requireExpiresAt()));
      case "WRONG_STATE" -> new FreezeResult.WrongState(HoldStatus.valueOf(reply.holdStatus()));
      default -> throw new IllegalStateException("freeze.lua answered " + reply.status());
    };
  }

  @Override
  public Optional<Hold> findHold(UUID eventId, UUID holdId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(holdId, "holdId must not be null");

    List<String> stored =
        redis
            .<String, String>opsForHash()
            .multiGet(
                SeatKeys.hold(eventId, holdId), List.of("userId", "seats", "expiresAt", "status"));
    String userId = stored.get(0);
    if (userId == null) {
      return Optional.empty();
    }
    return Optional.of(
        new Hold(
            holdId,
            eventId,
            UUID.fromString(userId),
            List.of(stored.get(1).split(",")),
            Instant.ofEpochMilli(Long.parseLong(stored.get(2))),
            HoldStatus.valueOf(stored.get(3))));
  }

  @Override
  public void dropDueEntry(UUID eventId, UUID holdId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(holdId, "holdId must not be null");
    redis.opsForZSet().remove(SeatKeys.holdExpiry(eventId), holdId.toString());
  }

  @Override
  public List<UUID> findDueHolds(UUID eventId, Instant now, int limit) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(now, "now must not be null");
    if (limit <= 0) {
      throw new IllegalArgumentException("limit must be positive, was " + limit);
    }

    Set<String> due =
        redis
            .opsForZSet()
            .rangeByScore(
                SeatKeys.holdExpiry(eventId),
                Double.NEGATIVE_INFINITY,
                now.toEpochMilli(),
                0,
                limit);
    return Stream.ofNullable(due).flatMap(Set::stream).map(UUID::fromString).toList();
  }

  private static ReleaseResult releaseResultOf(ScriptReply reply) {
    return switch (reply.status()) {
      case "OK" -> new ReleaseResult.Released(reply.requireVersion(), reply.freedSeatsOrZero());
      case "NOT_FOUND" -> new ReleaseResult.NotFound();
      case "FORBIDDEN" -> new ReleaseResult.NotTheOwner();
      case "NOT_DUE" -> new ReleaseResult.NotDue();
      case "WRONG_STATE" -> new ReleaseResult.WrongState(HoldStatus.valueOf(reply.holdStatus()));
      default -> throw new IllegalStateException("a release script answered " + reply.status());
    };
  }

  /**
   * How long the key that enforces one hold per user lives.
   *
   * <p>The lifetime of the hold plus a minute, as a relative duration: Redis expires a key against
   * its own wall clock, while the service reads an injected one, and a test that moves its clock
   * must not end up writing a key that was already expired when it was written.
   */
  private static long userHoldTtlMillis(HoldRequest request) {
    long untilExpiry = request.expiresAt().toEpochMilli() - request.now().toEpochMilli();
    return untilExpiry + Duration.ofMinutes(1).toMillis();
  }

  /**
   * The keys the release and the expiry scripts touch, in the order they declare them.
   *
   * <p>The key of the user comes from the hold, so a caller cannot point the script at somebody
   * else's key. Even if it did, the script only clears that key when it still names this hold,
   * which means a wrong key changes nothing rather than freeing the wrong user.
   */
  private static List<String> holdKeys(Hold hold) {
    UUID eventId = hold.eventId();
    return List.of(
        SeatKeys.seats(eventId),
        SeatKeys.version(eventId),
        SeatKeys.available(eventId),
        SeatKeys.holdExpiry(eventId),
        SeatKeys.userHold(eventId, hold.userId()),
        SeatKeys.hold(eventId, hold.id()),
        SeatKeys.seatEvents(eventId));
  }

  private ScriptReply run(RedisScript<String> script, List<String> keys, List<String> args) {
    String json = redis.execute(script, keys, args.toArray());
    if (json == null) {
      throw new IllegalStateException("a hold path script answered nothing");
    }
    try {
      return REPLIES.readValue(json, ScriptReply.class);
    } catch (JacksonException e) {
      throw new IllegalStateException("a hold path script answered " + json, e);
    }
  }
}
