package com.seatrush.queue.adapters.out.redis;

import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueRegistry;
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
 * The {@link QueueRegistry} on Redis: two Lua scripts for the writes and two reads around them.
 *
 * <p>The scripts answer with JSON, which is what lets one call carry an outcome and the two
 * counters a position is made of. The mapper is private to this adapter: the protocol between this
 * class and the scripts is internal and must not change because the public API changed how it
 * writes JSON.
 *
 * <p>Reading a place is the one operation that is not a script, because it does not need to be: two
 * {@code GET}s of keys on the same slot, fetched together, and nothing is being changed. A user
 * whose sequence is read a microsecond before an admission moves the head is told they are one
 * place further back than they are, and will be told otherwise on their next poll.
 */
@Repository
public class RedisQueueRegistry implements QueueRegistry {

  /**
   * How long the sequence key of one user survives without being touched.
   *
   * <p>There is one such key per user per sale, so without a ttl a benchmark of 200,000 users would
   * leave 200,000 keys behind for ever. A day is far longer than any sale: an on-sale is over in
   * minutes, and a key still untouched after twenty-four hours belongs to an event nobody is
   * queueing for. The event level keys are four per sale and are left alone.
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
            QueueKeys.userSequence(eventId, userId));
    List<String> args =
        List.of(
            userId.toString(),
            request.intoLobby() ? "1" : "0",
            Long.toString(USER_SEQUENCE_TTL_SECONDS));

    JoinReply reply = replyOf(redis.execute(scripts.join(), keys, args.toArray()));
    return switch (reply.status()) {
      case "LOBBY" -> QueuePlace.inLobby(eventId, userId);
      case "SEQUENCED" ->
          QueuePlace.withSequence(eventId, userId, reply.requireSequence(), reply.headOrZero());
      default ->
          throw new IllegalStateException("join.lua answered an unknown status " + reply.status());
    };
  }

  @Override
  public Optional<QueuePlace> place(UUID eventId, UUID userId) {
    Objects.requireNonNull(eventId, "eventId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    List<String> values =
        redis
            .opsForValue()
            .multiGet(List.of(QueueKeys.userSequence(eventId, userId), QueueKeys.head(eventId)));
    String sequence = values == null || values.isEmpty() ? null : values.get(0);
    if (sequence == null) {
      // No sequence: either in the lobby, or not waiting at all. The set answers which.
      return Boolean.TRUE.equals(
              redis.opsForSet().isMember(QueueKeys.lobby(eventId), userId.toString()))
          ? Optional.of(QueuePlace.inLobby(eventId, userId))
          : Optional.empty();
    }
    String head = values.size() > 1 ? values.get(1) : null;
    return Optional.of(
        QueuePlace.withSequence(eventId, userId, parse(sequence, "sequence"), headOf(head)));
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
      try {
        users.add(UUID.fromString(member));
      } catch (IllegalArgumentException notAUserId) {
        // Loud rather than skipped. Nothing this service writes could produce it, so either
        // something else is writing to this key or the key format changed under a running
        // deployment; both are worth a failed open and an alert rather than a quiet half lobby.
        throw new IllegalStateException(
            "the lobby of event " + eventId + " holds " + member + ", which is not a user id",
            notAUserId);
      }
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

  private static JoinReply replyOf(String json) {
    if (json == null) {
      throw new IllegalStateException("join.lua answered nothing");
    }
    try {
      return REPLIES.readValue(json, JoinReply.class);
    } catch (JacksonException notAReply) {
      throw new IllegalStateException("join.lua answered " + json, notAReply);
    }
  }

  private static long headOf(String head) {
    return head == null ? 0 : parse(head, "head");
  }

  private static long parse(String value, String what) {
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException notANumber) {
      throw new IllegalStateException("the " + what + " of a queue is " + value, notANumber);
    }
  }
}
