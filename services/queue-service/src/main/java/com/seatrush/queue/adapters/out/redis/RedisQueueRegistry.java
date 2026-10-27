package com.seatrush.queue.adapters.out.redis;

import com.seatrush.queue.domain.AdmissionBatch;
import com.seatrush.queue.domain.AdmitRequest;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The {@link QueueRegistry} on Redis: five Lua scripts and one plain read beside them.
 *
 * <p>The scripts answer with JSON, which is what lets one call carry an outcome, the two counters a
 * position is made of and the slot that may come with them. The mapper is private to this adapter:
 * the protocol between this class and the scripts is internal and must not change because the
 * public API changed how it writes JSON.
 *
 * <p>Reading a place is a script as well, although it changes nothing. The facts it reads have to
 * agree with each other, and the busiest read of a flash on-sale is worth one round trip rather
 * than three; {@code place.lua} says the rest.
 *
 * <p>The one plain read left is the lobby, because the shuffle has to happen outside Redis anyway
 * and reading a set is all that takes.
 */
@Repository
public class RedisQueueRegistry implements QueueRegistry {

  /**
   * How long the sequence key of one user survives without being touched.
   *
   * <p>There is one such key per user per sale, so without a ttl a benchmark of 200,000 users would
   * leave 200,000 keys behind for ever. A day is far longer than any sale: an on-sale is over in
   * minutes, and a key still untouched after twenty-four hours belongs to an event nobody is
   * queueing for. The event level keys are five per sale and are left alone.
   */
  static final long USER_SEQUENCE_TTL_SECONDS = 24 * 60 * 60L;

  private static final JsonMapper REPLIES =
      JsonMapper.builder().disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

  private final StringRedisTemplate redis;
  private final QueueScripts scripts;

  public RedisQueueRegistry(StringRedisTemplate redis, QueueScripts scripts) {
    this.redis = redis;
    this.scripts = scripts;
  }

  @Override
  public QueuePlace join(JoinRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    UUID eventId = request.eventId();
    UUID userId = request.userId();

    List<String> keys =
        List.of(
            QueueKeys.lobby(eventId),
            QueueKeys.queue(eventId),
            QueueKeys.sequence(eventId),
            QueueKeys.head(eventId),
            QueueKeys.userSequence(eventId, userId),
            QueueKeys.active(eventId));
    Object[] args = {
      userId.toString(),
      request.intoLobby() ? "1" : "0",
      Long.toString(USER_SEQUENCE_TTL_SECONDS),
      Long.toString(request.now().toEpochMilli())
    };

    PlaceReply reply = replyOf(redis.execute(scripts.join(), keys, args), "join.lua");
    return placeOf(eventId, userId, reply)
        .orElseThrow(() -> new IllegalStateException("join.lua put nobody anywhere"));
  }

  @Override
  public Optional<QueuePlace> place(UUID eventId, UUID userId, Instant now) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");
    Objects.requireNonNull(now, "now must not be null");

    List<String> keys =
        List.of(
            QueueKeys.lobby(eventId),
            QueueKeys.head(eventId),
            QueueKeys.userSequence(eventId, userId),
            QueueKeys.active(eventId));
    Object[] args = {userId.toString(), Long.toString(now.toEpochMilli())};

    return placeOf(
        eventId, userId, replyOf(redis.execute(scripts.place(), keys, args), "place.lua"));
  }

  @Override
  public AdmissionBatch admit(AdmitRequest request) {
    Objects.requireNonNull(request, "request must not be null");
    UUID eventId = request.eventId();

    List<String> keys =
        List.of(QueueKeys.queue(eventId), QueueKeys.active(eventId), QueueKeys.head(eventId));
    Object[] args = {
      Long.toString(request.now().toEpochMilli()),
      Long.toString(request.admissionExpiresAt().toEpochMilli()),
      Integer.toString(request.maxToAdmit()),
      Integer.toString(request.maxActiveUsers())
    };

    String json = redis.execute(scripts.admit(), keys, args);
    if (json == null) {
      throw new IllegalStateException("admit.lua answered nothing");
    }
    AdmitReply reply;
    try {
      reply = REPLIES.readValue(json, AdmitReply.class);
    } catch (JacksonException notAReply) {
      throw new IllegalStateException("admit.lua answered " + json, notAReply);
    }
    return new AdmissionBatch(
        eventId,
        reply.admitted().stream().map(RedisQueueRegistry::userId).toList(),
        reply.requireHead(),
        reply.requireActiveUsers(),
        reply.requireFreedSlots());
  }

  @Override
  public boolean leave(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    List<String> keys =
        List.of(
            QueueKeys.lobby(eventId),
            QueueKeys.queue(eventId),
            QueueKeys.userSequence(eventId, userId));
    Long removed = redis.execute(scripts.leave(), keys, userId.toString());
    return removed != null && removed > 0;
  }

  @Override
  public List<UUID> lobby(UUID eventId) {
    Objects.requireNonNull(eventId, "eventId must not be null");

    Set<String> members = redis.opsForSet().members(QueueKeys.lobby(eventId));
    if (members == null || members.isEmpty()) {
      return List.of();
    }
    List<UUID> users = new ArrayList<>(members.size());
    for (String member : members) {
      users.add(userId(member));
    }
    return users;
  }

  @Override
  public int openLobby(UUID eventId, List<UUID> users) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(users, "users must not be null");
    if (users.isEmpty()) {
      return 0;
    }

    List<String> keys = new ArrayList<>(users.size() + 3);
    keys.add(QueueKeys.lobby(eventId));
    keys.add(QueueKeys.queue(eventId));
    keys.add(QueueKeys.sequence(eventId));

    Object[] args = new Object[users.size() + 1];
    args[0] = Long.toString(USER_SEQUENCE_TTL_SECONDS);
    for (int i = 0; i < users.size(); i++) {
      UUID userId = users.get(i);
      keys.add(QueueKeys.userSequence(eventId, userId));
      args[i + 1] = userId.toString();
    }

    Long queued = redis.execute(scripts.openLobby(), keys, args);
    return queued == null ? 0 : queued.intValue();
  }

  /** The place one reply describes, which is nothing at all for a user who is not waiting. */
  private static Optional<QueuePlace> placeOf(UUID eventId, UUID userId, PlaceReply reply) {
    return switch (reply.status()) {
      case PlaceReply.NONE -> Optional.empty();
      case PlaceReply.LOBBY -> Optional.of(QueuePlace.inLobby(eventId, userId));
      case PlaceReply.SEQUENCED ->
          Optional.of(
              QueuePlace.withSequence(
                  eventId,
                  userId,
                  reply.requireSequence(),
                  reply.headOrZero(),
                  reply.admittedUntilOrNull()));
      default ->
          throw new IllegalStateException("a script answered an unknown status " + reply.status());
    };
  }

  private static PlaceReply replyOf(String json, String script) {
    if (json == null) {
      throw new IllegalStateException(script + " answered nothing");
    }
    try {
      return REPLIES.readValue(json, PlaceReply.class);
    } catch (JacksonException notAReply) {
      throw new IllegalStateException(script + " answered " + json, notAReply);
    }
  }

  /**
   * A member of one of these keys as a user id.
   *
   * <p>Loud rather than skipped. Nothing this service writes could produce anything else, so either
   * something else is writing to these keys or the key format changed under a running deployment;
   * both are worth a failure and an alert rather than a quietly half read waiting room.
   */
  private static UUID userId(String member) {
    try {
      return UUID.fromString(member);
    } catch (IllegalArgumentException notAUserId) {
      throw new IllegalStateException(
          "a waiting room holds " + member + ", which is not a user id", notAUserId);
    }
  }
}
