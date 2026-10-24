package com.seatrush.messaging.outbox;

import com.seatrush.messaging.config.OutboxProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes what the outbox is holding, and marks what the broker took (README section 9.5).
 *
 * <p>This is the second half of the Postgres outbox. The business transaction already decided that
 * the message exists; this class decides nothing and only moves it, which is why it can be running
 * on every pod at once without any coordination beyond {@code SKIP LOCKED}.
 *
 * <p>Claim, publish and mark share one transaction on purpose. A row is marked published only after
 * the broker has it, so a pod that dies in between publishes it twice rather than losing it: the
 * guarantee is at-least-once and the duplicate is caught by consumer idempotency. The cost is that
 * the rows stay locked for the length of a produce round trip, which is what {@code SKIP LOCKED}
 * makes harmless: another pod takes different rows instead of waiting for these.
 */
public class OutboxRelay {

  private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

  private final TransactionTemplate transactions;
  private final OutboxStore store;
  private final MessagePublisher publisher;
  private final OutboxProperties properties;
  private final Clock clock;

  /**
   * The age of the oldest row this relay last found waiting, in milliseconds.
   *
   * <p>Read by the lag gauge instead of a query of its own: the claim already knows the answer, and
   * asking the database again on every scrape would add a query per second per pod to say what the
   * relay just saw. It is the age of what was waiting, so it keeps growing while publication fails
   * and drops to zero on the first pass that finds the table empty.
   */
  private final AtomicLong lagMillis = new AtomicLong();

  private final Counter relayed;

  public OutboxRelay(
      TransactionTemplate transactions,
      OutboxStore store,
      MessagePublisher publisher,
      OutboxProperties properties,
      Clock clock,
      MeterRegistry meters) {
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.store = Objects.requireNonNull(store, "store must not be null");
    this.publisher = Objects.requireNonNull(publisher, "publisher must not be null");
    this.properties = Objects.requireNonNull(properties, "properties must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(meters, "meters must not be null");

    // The registry keeps only a weak reference to what a gauge observes, which here is the field
    // below and therefore this relay. That is correct for a singleton bean and is why the relay
    // must be one: a gauge whose subject has been collected reports NaN rather than a stale value.
    Gauge.builder("seatrush.outbox.lag.seconds", lagMillis, holder -> holder.get() / 1000.0)
        .tag("store", "postgres")
        .description("Age of the oldest unpublished outbox entry")
        .register(meters);
    this.relayed = Counter.builder("seatrush.outbox.relayed").register(meters);
  }

  /** One pass, on a schedule, on every pod. */
  @Scheduled(
      initialDelayString = "${seatrush.outbox.poll-interval:100ms}",
      fixedDelayString = "${seatrush.outbox.poll-interval:100ms}")
  public void relay() {
    try {
      relayOnce();
    } catch (RuntimeException e) {
      // Nothing is lost by a failed pass. The transaction rolled back, so no row was marked, and
      // the next pass claims the same rows again.
      log.warn("an outbox relay pass failed and will be retried on the next run", e);
    }
  }

  /**
   * Publishes what is waiting right now and reports how many rows that was.
   *
   * <p>Separate from the scheduled method so a test can run exactly one pass.
   */
  public int relayOnce() {
    Integer published = transactions.execute(status -> publishBatch());
    return published == null ? 0 : published;
  }

  private int publishBatch() {
    List<OutboxEntry> batch = store.claim(properties.batchSize());
    if (batch.isEmpty()) {
      lagMillis.set(0);
      return 0;
    }
    // The lowest id is not necessarily the oldest row: a sequence number is handed out before
    // the transaction that took it commits, so two concurrent writers can commit out of order.
    Instant oldest =
        batch.stream().map(OutboxEntry::createdAt).min(Comparator.naturalOrder()).orElseThrow();
    lagMillis.set(Duration.between(oldest, clock.instant()).toMillis());

    List<Long> published = publisher.publishAll(batch);
    store.markPublished(published);
    if (published.size() < batch.size()) {
      log.warn(
          "relayed {} of {} outbox rows, the rest stays unpublished",
          published.size(),
          batch.size());
    } else {
      log.debug("relayed {} outbox rows", published.size());
    }
    relayed.increment(published.size());
    return published.size();
  }
}
