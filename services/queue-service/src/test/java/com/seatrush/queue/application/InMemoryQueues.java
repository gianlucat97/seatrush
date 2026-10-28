package com.seatrush.queue.application;

import com.seatrush.queue.domain.AdmissionBatch;
import com.seatrush.queue.domain.AdmitRequest;
import com.seatrush.queue.domain.JoinRequest;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.QueueRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
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
 * <p>Not final, so that a test which cares how often the store is asked something can wrap a method
 * and count. The arithmetic of the stream exists precisely to avoid those reads, and a promise
 * about cost is only kept if something asserts it.
 *
 * <p>It keeps the rules the Lua scripts keep, because those rules are what the callers rely on: a
 * join is idempotent, a user already holding a sequence keeps it whatever the request asked for,
 * opening the lobby claims a user by removing them from it, and admitting frees the slots that have
 * expired before it counts the room left. What it deliberately does not reproduce is atomicity; the
 * scripts are tested against a real Redis, which is the only place that question can honestly be
 * asked.
 */
class InMemoryQueues implements QueueRegistry {

  private final Map<UUID, Set<UUID>> lobbies = new LinkedHashMap<>();
  private final Map<UUID, Map<UUID, Long>> sequences = new LinkedHashMap<>();
  private final Map<UUID, Map<UUID, Instant>> slots = new LinkedHashMap<>();
  private final Map<UUID, AtomicLong> counters = new LinkedHashMap<>();
  private final Map<UUID, Long> heads = new LinkedHashMap<>();
  private final Set<UUID> closed = new LinkedHashSet<>();

  @Override
  public QueuePlace join(JoinRequest request) {
    UUID eventId = request.eventId();
    UUID userId = request.userId();

    Long existing = sequencesOf(eventId).get(userId);
    if (existing != null) {
      return placeOf(eventId, userId, existing, request.now());
    }
    if (request.intoLobby()) {
      lobbyOf(eventId).add(userId);
      return QueuePlace.inLobby(eventId, userId);
    }
    lobbyOf(eventId).remove(userId);
    return placeOf(eventId, userId, nextSequence(eventId, userId), request.now());
  }

  @Override
  public Optional<QueuePlace> place(UUID eventId, UUID userId, Instant now) {
    Long sequence = sequencesOf(eventId).get(userId);
    if (sequence != null) {
      return Optional.of(placeOf(eventId, userId, sequence, now));
    }
    return lobbyOf(eventId).contains(userId)
        ? Optional.of(QueuePlace.inLobby(eventId, userId))
        : Optional.empty();
  }

  @Override
  public AdmissionBatch admit(AdmitRequest request) {
    UUID eventId = request.eventId();
    Map<UUID, Instant> active = slotsOf(eventId);

    int freed = 0;
    for (UUID admitted : List.copyOf(active.keySet())) {
      if (!active.get(admitted).isAfter(request.now())) {
        active.remove(admitted);
        freed++;
      }
    }

    int room = Math.min(request.maxToAdmit(), request.maxActiveUsers() - active.size());
    long headBefore = headOf(eventId);
    long head = headBefore;
    List<UUID> admitted = new ArrayList<>();
    if (room > 0) {
      List<Map.Entry<UUID, Long>> nextInLine =
          sequencesOf(eventId).entrySet().stream()
              .filter(entry -> entry.getValue() > headBefore)
              .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
              .limit(room)
              .toList();
      for (Map.Entry<UUID, Long> next : nextInLine) {
        active.put(next.getKey(), request.admissionExpiresAt());
        admitted.add(next.getKey());
        head = Math.max(head, next.getValue());
      }
      if (!admitted.isEmpty()) {
        heads.put(eventId, head);
      }
    }
    return new AdmissionBatch(eventId, admitted, head, active.size(), freed);
  }

  @Override
  public long head(UUID eventId) {
    return headOf(eventId);
  }

  @Override
  public void close(UUID eventId) {
    lobbyOf(eventId).clear();
    closed.add(eventId);
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

  /** The sales whose waiting room was closed, which is what a sold out event leaves behind. */
  Set<UUID> closedRooms() {
    return Set.copyOf(closed);
  }

  /** Moves the admission head without handing out a slot, for the position arithmetic alone. */
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

  private QueuePlace placeOf(UUID eventId, UUID userId, long sequence, Instant now) {
    Instant admittedUntil = slotsOf(eventId).get(userId);
    return QueuePlace.withSequence(
        eventId,
        userId,
        sequence,
        headOf(eventId),
        admittedUntil != null && admittedUntil.isAfter(now) ? admittedUntil : null);
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

  private Map<UUID, Instant> slotsOf(UUID eventId) {
    return slots.computeIfAbsent(eventId, id -> new LinkedHashMap<>());
  }

  private long headOf(UUID eventId) {
    return heads.getOrDefault(eventId, 0L);
  }
}
