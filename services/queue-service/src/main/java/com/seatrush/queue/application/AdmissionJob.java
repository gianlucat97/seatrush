package com.seatrush.queue.application;

import com.seatrush.queue.config.AdmissionProperties;
import com.seatrush.queue.domain.AdmissionBatch;
import com.seatrush.queue.domain.AdmitRequest;
import com.seatrush.queue.domain.QueueRegistry;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The door of every live sale, opened once an interval (README section 6.2).
 *
 * <p>It does two things that look separate and are not. It frees the slots of buyers whose
 * admission has expired, and it lets in as many of the next in line as the purchase area has room
 * for. The room is exactly what the freeing just made, which is why both happen inside one script
 * rather than in a sweeper and a job that would have to agree with each other.
 *
 * <p>It runs on every pod at the same time, with no leader election and no lock, like the hold
 * sweeper of inventory. What makes that safe is {@code admit.lua}: taking a user off the queue is
 * what claims them, so two pods passing at the same moment share the batch between them rather than
 * admitting the same buyer twice. What it costs is that the rate is per pod, which is the one thing
 * about this that a deployment has to know: three pods at 500 a second admit 1,500 a second.
 *
 * <p>It mints nothing. The admission token is signed when a client asks where it stands, from the
 * expiry of the slot written here, so there is nowhere for a bearer token to be stored and nothing
 * to lose if a push is missed. What this job writes is the fact; the token is a reading of it.
 */
@Component
public class AdmissionJob {

  private static final Logger log = LoggerFactory.getLogger(AdmissionJob.class);

  private final SaleCalendar calendar;
  private final QueueRegistry registry;
  private final AdmissionRateEstimator rates;
  private final AdmissionProperties properties;
  private final Duration admissionTtl;
  private final Clock clock;

  /**
   * @param tokens the admission signer, here only for the lifetime it is configured with. The slot
   *     this job writes and the token that will be minted against it have to end at the same
   *     instant, and taking the figure from the thing that signs is how they cannot drift apart
   */
  public AdmissionJob(
      SaleCalendar calendar,
      QueueRegistry registry,
      AdmissionRateEstimator rates,
      AdmissionProperties properties,
      AdmissionTokenSigner tokens,
      Clock clock) {
    this.calendar = calendar;
    this.registry = registry;
    this.rates = rates;
    this.properties = properties;
    this.admissionTtl = tokens.ttl();
    this.clock = clock;
  }

  /**
   * One pass over every sale that is live.
   *
   * <p>A fixed delay and not a fixed rate: a pass delayed by a slow Redis must not have the next
   * one queued behind it, because admitting twice in a row the moment it recovers would be a burst
   * the purchase area was sized to avoid.
   */
  @Scheduled(
      fixedDelayString = "${seatrush.admission.interval:1s}",
      initialDelayString = "${seatrush.admission.interval:1s}")
  public void admit() {
    for (SaleEvent event : calendar.selling()) {
      try {
        admitFrom(event);
      } catch (RuntimeException failure) {
        // One sale whose door jammed must not shut the others. The next pass tries again, and
        // nothing here has been half done: the script is one step or none.
        log.error("the door of event {} could not be opened", event.id(), failure);
      }
    }
  }

  /**
   * Opens the door of one sale.
   *
   * @return what the pass did, so that a caller driving this by hand can assert on it
   */
  public AdmissionBatch admitFrom(SaleEvent event) {
    // Whole seconds, because the admission tokens minted against this slot carry an expiry a JWT
    // can hold, which is seconds. Rounding here means the slot and the token are the same instant
    // rather than one of them a fraction of a second past the other.
    Instant now = clock.instant().truncatedTo(ChronoUnit.SECONDS);

    AdmissionBatch batch =
        registry.admit(
            new AdmitRequest(
                event.id(),
                now,
                now.plus(admissionTtl),
                properties.maxPerPass(),
                properties.maxActiveUsers()));

    rates.observe(event.id(), now, batch.head());

    if (!batch.isEmpty() || batch.freedSlots() > 0) {
      log.info(
          "event {} ({}): {} buyers admitted, {} expired slots freed, {} of {} active, head at {}",
          event.id(),
          event.name(),
          batch.size(),
          batch.freedSlots(),
          batch.activeUsers(),
          properties.maxActiveUsers(),
          batch.head());
    }
    return batch;
  }
}
