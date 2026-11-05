package com.seatrush.order.application;

import com.seatrush.order.config.CheckoutProperties;
import com.seatrush.order.domain.SagaInstance;
import com.seatrush.order.domain.SagaRepository;
import com.seatrush.order.domain.StuckSagas;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The half of the SAGA that nothing replies to (README section 9.7).
 *
 * <p>Two things can go wrong by silence rather than by answer. A payment that is never authorized
 * leaves a checkout waiting with seats held, and the deadline is what gives up on it. A capture
 * that is never answered leaves an order with its seats sold and the money untaken, and the retry
 * is what keeps asking. Neither has a message to be triggered by, so both are polled.
 *
 * <p>It runs on every pod at once with no leader election, and {@code SKIP LOCKED} is what makes
 * that safe: a pod takes the rows nobody else holds, and the optimistic lock settles any reply that
 * reached the same SAGA in the meantime. Each pass is one transaction, so a pod that dies mid-pass
 * releases its rows to the next one.
 */
@Component
public class SagaScheduler {

  private static final Logger log = LoggerFactory.getLogger(SagaScheduler.class);

  private final SagaRepository sagas;
  private final CheckoutSaga saga;
  private final TransactionTemplate transactions;
  private final Clock clock;
  private final CheckoutProperties properties;

  /**
   * What this pod last found still stuck, which is what the two alerts of README section 14.4 read.
   *
   * <p>Measured here because this is the thing that is supposed to clear both of them. A deadline
   * that has passed is compensated within one interval and a capture is retried until its limit, so
   * a number above zero taken straight after a pass is not a slow payment provider: it is work this
   * scheduler did not manage to do.
   *
   * <p>Sampled on the scheduler's own interval rather than queried by the gauge, for the reason the
   * outbox lag gauge gives: a scrape must not be able to block on the database.
   *
   * <p>Every pod publishes its own reading of one shared table, so these two have to be aggregated
   * with a max or an average across instances and never with a sum.
   */
  private final AtomicLong overdue = new AtomicLong();

  private final AtomicLong capturesAbandoned = new AtomicLong();

  public SagaScheduler(
      SagaRepository sagas,
      CheckoutSaga saga,
      TransactionTemplate transactions,
      Clock clock,
      CheckoutProperties properties,
      MeterRegistry meters) {
    this.sagas = sagas;
    this.saga = saga;
    this.transactions = transactions;
    this.clock = clock;
    this.properties = properties;

    // Weakly referenced by the registry, like every gauge here, so the subject is a field of a
    // singleton bean and not a number this constructor hands over.
    Gauge.builder("seatrush.saga.stuck", overdue, AtomicLong::get)
        .tag("reason", "overdue")
        .description("Checkouts the scheduler has not managed to move on")
        .register(meters);
    Gauge.builder("seatrush.saga.stuck", capturesAbandoned, AtomicLong::get)
        .tag("reason", "capture_abandoned")
        .description("Checkouts the scheduler has not managed to move on")
        .register(meters);
  }

  /** One pass over the checkouts that have run out of time or are due another capture. */
  @Scheduled(
      initialDelayString = "${seatrush.checkout.scheduler-interval}",
      fixedDelayString = "${seatrush.checkout.scheduler-interval}")
  public void sweep() {
    try {
      sweepOnce();
    } catch (RuntimeException e) {
      // Nothing is lost by a failed pass. The transaction rolled back, so no SAGA was moved, and
      // the next pass finds the same rows still overdue.
      log.warn("a scheduler pass failed and will be retried on the next run", e);
    }
  }

  /**
   * Times out and retries what is due right now, and reports how many SAGAs that was.
   *
   * <p>Separate from the scheduled method so a test can run exactly one pass.
   */
  public int sweepOnce() {
    int moved = timeOutOverdue() + retryCaptures();
    measureStuck();
    return moved;
  }

  /**
   * Reads what is still stuck after this pass.
   *
   * <p>A failure here keeps the previous reading and does not fail the pass: the sweep is the work
   * and this is the report of it, so a count that could not be taken must not undo a compensation
   * that was.
   */
  private void measureStuck() {
    try {
      StuckSagas stuck = sagas.countStuck(clock.instant(), properties.captureMaxRetries());
      overdue.set(stuck.overdue());
      capturesAbandoned.set(stuck.capturesAbandoned());
    } catch (RuntimeException failure) {
      log.warn("the stuck checkouts could not be counted and keep their last reading", failure);
    }
  }

  /**
   * Compensates the checkouts whose deadline has passed.
   *
   * <p>Only ever a checkout waiting for its authorization, because that is the only step a deadline
   * is allowed on: once the commit has gone out the reply is waited for indefinitely, which is what
   * removes the race of a timeout firing while the seats were being sold (README section 8.3).
   */
  private int timeOutOverdue() {
    Instant now = clock.instant();
    Integer compensated =
        transactions.execute(
            status -> {
              List<SagaInstance> overdue = sagas.findOverdue(now, properties.schedulerBatchSize());
              int started = 0;
              for (SagaInstance overdueSaga : overdue) {
                if (saga.timeOut(overdueSaga)) {
                  started++;
                }
              }
              return started;
            });
    return compensated == null ? 0 : compensated;
  }

  /** Sends the capture again for the orders whose attempt is due. */
  private int retryCaptures() {
    Instant now = clock.instant();
    Integer retried =
        transactions.execute(
            status -> {
              List<SagaInstance> due = sagas.findRetryDue(now, properties.schedulerBatchSize());
              int sent = 0;
              for (SagaInstance dueSaga : due) {
                if (saga.retryCapture(dueSaga)) {
                  sent++;
                }
              }
              return sent;
            });
    return retried == null ? 0 : retried;
  }
}
