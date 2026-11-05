package com.seatrush.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The store of SAGA instances (README section 9.7). */
public interface SagaRepository {

  /** Writes a new SAGA; the caller owns the transaction, which is the order's own. */
  void insert(SagaInstance saga);

  Optional<SagaInstance> findByOrderId(UUID orderId);

  /**
   * Writes the next state of a SAGA, if nobody else has moved it first.
   *
   * <p>The optimistic lock is the whole concurrency story of README section 9.7. A reply and a
   * timeout can reach one SAGA at the same moment on two pods, and both will have read the same
   * version; the one that writes second finds the version changed and is told so, which is what
   * keeps a timed out checkout from also being confirmed.
   *
   * @param next the state to write, carrying the version it was read at
   * @return whether this caller is the one that advanced it
   */
  boolean advance(SagaInstance next);

  /**
   * The SAGAs whose deadline has passed, locked for this transaction.
   *
   * <p>{@code SKIP LOCKED}, so the scheduler runs on every pod with no leader election: a pod takes
   * the SAGAs nobody else holds and leaves the rest to whoever has them.
   */
  List<SagaInstance> findOverdue(Instant now, int limit);

  /** The SAGAs with a capture attempt due, locked for this transaction the same way. */
  List<SagaInstance> findRetryDue(Instant now, int limit);

  /**
   * How many checkouts are stuck right now, for the gauges of README section 14.1.
   *
   * <p>Counts and not rows, and no lock of any kind: this is read to be published and never to be
   * acted on, so it must not take anything away from the pass that is there to fix it.
   *
   * <p>Unbounded by a batch size, unlike the two queries above. A scheduler takes a limited batch
   * because it has work to do per row; a gauge that stopped counting at 500 would report a healthy
   * number for the deployment that most needs the alert.
   *
   * @param maxRetries the retry limit of {@code seatrush.checkout.capture-max-retries}, which is
   *     what makes a capture abandoned rather than merely waiting
   */
  StuckSagas countStuck(Instant now, int maxRetries);
}
