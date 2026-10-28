package com.seatrush.queue.domain;

import java.time.Instant;
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

  /**
   * Where a user stands, or nothing if they are waiting for neither.
   *
   * <p>One consistent answer and not four reads that might disagree: a sequence, the head it is
   * measured against and the slot it may come with have to be seen at the same instant, or a buyer
   * could be told they are in the purchase area with nothing to be there with.
   *
   * @param now what counts as an expired slot, from the caller's clock
   */
  Optional<QueuePlace> place(UUID eventId, UUID userId, Instant now);

  /**
   * Lets the next buyers of a sale through the door.
   *
   * <p>Frees the slots that have expired, admits whatever is left of {@code maxActiveUsers} capped
   * by the rate, and moves the head. All of it in one step, because the room to let somebody in is
   * exactly what the freeing just made and reading the two separately would oversubscribe the
   * purchase area.
   *
   * <p>Safe on every pod at once, with no leader and no lock: taking a user off the queue is what
   * claims them, so two pods passing at the same moment share a batch rather than admitting the
   * same buyer twice.
   */
  AdmissionBatch admit(AdmitRequest request);

  /**
   * The highest sequence the door has let through, 0 before the first admission.
   *
   * <p>One read that places every waiting user at once, which is what a stream pushing positions to
   * thousands of them needs: their sequences cannot change, so the head is the only half of {@code
   * sequence - head} that has to be asked for again.
   */
  long head(UUID eventId);

  /**
   * Takes a user out of the lobby and out of the queue.
   *
   * @return whether they were anywhere to begin with
   */
  boolean leave(UUID eventId, UUID userId);

  /** Everybody currently in the lobby of an event, in no meaningful order. */
  List<UUID> lobby(UUID eventId);

  /**
   * Gives back the purchase area slot of one buyer.
   *
   * <p>Called when a checkout has reached a terminal state, which is the one moment somebody is
   * known to be finished with the room they were let into (README section 6.2). The slot would have
   * been freed anyway when the admission expired, so what this buys is the minutes in between, and
   * during an on-sale those minutes are somebody else's turn.
   *
   * <p>Idempotent, because the message that triggers it can be delivered twice and because two pods
   * may read the same one: removing a member that is not there removes nothing.
   *
   * @return whether a slot was actually given back
   */
  boolean releaseSlot(UUID eventId, UUID userId);

  /**
   * Drops the lobby and the queue of a sale that is over.
   *
   * <p>What it frees is the two collections a flash on-sale fills: a set and a sorted set of two
   * hundred thousand members each, for an event nobody can join any more. The counters and the
   * per-user sequences are left where they are, the second lot because they expire by themselves
   * and both because a client polling after the sale closed still gets a coherent answer out of
   * them rather than a position that suddenly reads as if the queue had emptied.
   *
   * <p>Idempotent, like everything else here: every pod may close the same sale.
   */
  void close(UUID eventId);

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
