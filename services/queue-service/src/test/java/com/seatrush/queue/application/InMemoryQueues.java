package com.seatrush.queue.application;

import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueRegistry;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link QueueRegistry} in a few maps, for the tests of the layer above it.
 *
 * <p>It keeps the rules the Lua scripts keep, because those rules are what the callers rely on: a
 * join is idempotent, a user already holding a sequence keeps it whatever the request asked for,
 * and opening the lobby claims a user by removing them from it. What it deliberately does not
 * reproduce is atomicity; the scripts are tested against a real Redis, which is the only place that
 * question can honestly be asked.
 */
final class InMemoryQueues implements QueueRegistry {

  private final Map<UUID, Set<UUID>> lobbies = new LinkedHashMap<>();
  private final Map<UUID, Map<UUID, Long>> sequences = new LinkedHashMap<>();
  private final Map<UUID, AtomicLong> counters = new LinkedHashMap<>();
  private final Map<UUID, Long> heads = new LinkedHashMap<>();

  @Override
  public QueuePlace join(JoinRequest request) {
    UUID eventId = request.eventId();
    UUID userId = request.userId();

    Long existing = sequencesOf(eventId).get(userId);
    if (existing != null) {
      return QueuePlace.withSequence(eventId, userId, existing, headOf(eventId));
    }
    if (request.intoLobby()) {
      lobbyOf(eventId).add(userId);
      return QueuePlace.inLobby(eventId, userId);
    }
    lobbyOf(eventId).remove(userId);
    return QueuePlace.withSequence(eventId, userId, nextSequence(eventId, userId), headOf(eventId));
  }

  @Override
  public Optional<QueuePlace> place(UUID eventId, UUID userId) {
    Long sequence = sequencesOf(eventId).get(userId);
    if (sequence != null) {
      return Optional.of(QueuePlace.withSequence(eventId, userId, sequence, headOf(eventId)));
    }
    return lobbyOf(eventId).contains(userId)
        ? Optional.of(QueuePlace.inLobby(eventId, userId))
        : Optional.empty();
  }

  @Override
  public boolean leave(UUID eventId, UUID userId) {
    boolean wasInLobby = lobbyOf(eventId).remove(userId);
    boolean wasQueued = sequencesOf(eventId).remove(userId) != null;
    return wasInLobby || wasQueued;
  }

  @Override
  public List<UUID> lobby(UUID eventId) {
    return List.copyOf(lobbyOf(eventId));
  }

  @Override
  public int openLobby(UUID eventId, List<UUID> users) {
    int queued = 0;
    for (UUID userId : users) {
      if (lobbyOf(eventId).remove(userId) && !sequencesOf(eventId).containsKey(userId)) {
        nextSequence(eventId, userId);
        queued++;
      }
    }
    return queued;
  }

  /** Moves the admission head, which is what the admission job of the next feature will do. */
  void admitUpTo(UUID eventId, long head) {
    heads.put(eventId, head);
  }

  /** The queue in the order its sequences were handed out, which is the order of admission. */
  List<UUID> queued(UUID eventId) {
    return sequencesOf(eventId).entrySet().stream()
        .sorted(Map.Entry.comparingByValue())
        .map(Map.Entry::getKey)
        .toList();
  }

  private long nextSequence(UUID eventId, UUID userId) {
    long sequence = counters.computeIfAbsent(eventId, id -> new AtomicLong()).incrementAndGet();
    sequencesOf(eventId).put(userId, sequence);
    return sequence;
  }

  private Set<UUID> lobbyOf(UUID eventId) {
    return lobbies.computeIfAbsent(eventId, id -> new LinkedHashSet<>());
  }

  private Map<UUID, Long> sequencesOf(UUID eventId) {
    return sequences.computeIfAbsent(eventId, id -> new LinkedHashMap<>());
  }

  private long headOf(UUID eventId) {
    return heads.getOrDefault(eventId, 0L);
  }
}
