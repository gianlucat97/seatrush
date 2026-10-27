package com.seatrush.queue.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The lobby and the queue of every sale, wherever they are kept (README section 9.2).
 *
 * <p>Every method here is safe to call from any number of pods at the same time. That is not a
 * remark about this interface but a requirement on whatever implements it: a waiting room with a
 * leader would have a single point of failure precisely during the minute it matters, so instead
 * every operation is made atomic by the store and the pods are allowed to collide.
 */
public interface QueueRegistry {

  /**
   * Puts a user in the lobby or in the queue, and answers where they now stand.
   *
   * <p>Idempotent, as README section 6.2 requires: a client that joins twice, or two tabs of the
   * same browser, get the place the user already had rather than a second one further back. A user
   * already holding a sequence keeps it even if the request asked for the lobby.
   */
  QueuePlace join(JoinRequest request);

  /** Where a user stands, or nothing if they are waiting for neither. */
  Optional<QueuePlace> place(UUID eventId, UUID userId);

  /**
   * Takes a user out of the lobby and out of the queue.
   *
   * @return whether they were anywhere to begin with
   */
  boolean leave(UUID eventId, UUID userId);

  /** Everybody currently in the lobby of an event, in no meaningful order. */
  List<UUID> lobby(UUID eventId);

  /**
   * Appends lobby members to the queue in the order given, skipping any who are already queued.
   *
   * <p>The order is the caller's: the shuffle that makes arriving early worthless happens outside
   * the store, because a shuffle inside a Lua script would have to be deterministic to be
   * replication-safe, and a deterministic shuffle is a fixed order with extra steps.
   *
   * @return how many of them this call actually queued
   */
  int openLobby(UUID eventId, List<UUID> users);
}
