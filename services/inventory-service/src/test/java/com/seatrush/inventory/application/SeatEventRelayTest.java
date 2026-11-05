package com.seatrush.inventory.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.config.StreamRelayProperties;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatEventPublisher;
import com.seatrush.inventory.domain.SeatEventRecord;
import com.seatrush.inventory.domain.SeatEventStream;
import com.seatrush.inventory.domain.Section;
import com.seatrush.testsupport.time.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What the relay does with what the stream hands it, without a Redis or a broker.
 *
 * <p>The interesting behaviour is not the publishing: it is which entries are acknowledged. A relay
 * that acknowledged more than it published would lose seat events, which is the one thing the Redis
 * outbox exists to prevent.
 */
class SeatEventRelayTest {

  private static final Instant HAPPENED_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final InMemoryCatalog catalog = new InMemoryCatalog();
  private final RecordingStream stream = new RecordingStream();
  private final RecordingPublisher publisher = new RecordingPublisher();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MutableClock clock = MutableClock.at(HAPPENED_AT);
  private final SeatEventRelay relay =
      new SeatEventRelay(
          catalog,
          stream,
          publisher,
          new StreamRelayProperties(Duration.ofMillis(100), 10, Duration.ofSeconds(30)),
          meters,
          clock);

  @Test
  void relaysWhatTheStreamOfAnOpenSaleHolds() {
    UUID eventId = anOpenSale();
    stream.hasEntries(eventId, entry("1-0", "SeatsHeld", 1L), entry("2-0", "SeatsReleased", 2L));

    assertThat(relay.relayOnce()).isEqualTo(2);
    assertThat(publisher.published())
        .extracting(SeatEventRecord::recordId)
        .containsExactly("1-0", "2-0");
    assertThat(stream.acknowledged(eventId)).containsExactly("1-0", "2-0");
    assertThat(stream.pendingCount(eventId)).isZero();
  }

  @Test
  void acknowledgesOnlyWhatReachedTheBroker() {
    UUID eventId = anOpenSale();
    stream.hasEntries(
        eventId,
        entry("1-0", "SeatsHeld", 1L),
        entry("2-0", "SeatsHeld", 2L),
        entry("3-0", "SeatsHeld", 3L));
    publisher.failsAfter(1);

    assertThat(relay.relayOnce()).isEqualTo(1);
    assertThat(stream.acknowledged(eventId))
        .as("the entries past the gap stay pending and are read again")
        .containsExactly("1-0");
  }

  @Test
  void takesOverTheEntriesAnotherRelayAbandonedFirst() {
    UUID eventId = anOpenSale();
    stream.hasStaleEntries(eventId, entry("1-0", "SeatsHeld", 1L));
    stream.hasEntries(eventId, entry("2-0", "SeatsHeld", 2L));

    relay.relayOnce();

    assertThat(publisher.published())
        .as("the oldest change of a seat map is published first")
        .extracting(SeatEventRecord::recordId)
        .containsExactly("1-0", "2-0");
  }

  @Test
  void leavesTheStreamsOfSalesThatAreNotOpenAlone() {
    UUID draft = catalogEvent();
    stream.hasEntries(draft, entry("1-0", "SeatsHeld", 1L));

    assertThat(relay.relayOnce()).isZero();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void anEmptyStreamIsNotAFailure() {
    anOpenSale();

    assertThat(relay.relayOnce()).isZero();
    assertThat(publisher.published()).isEmpty();
  }

  @Test
  void aFailedPassDoesNotEscapeTheSchedule() {
    UUID eventId = anOpenSale();
    stream.hasEntries(eventId, entry("1-0", "SeatsHeld", 1L));
    publisher.alwaysThrows();

    relay.relay();

    assertThat(stream.acknowledged(eventId)).isEmpty();
  }

  @Test
  void publishesTheAgeOfTheOldestEntryItFound() {
    UUID eventId = anOpenSale();
    stream.hasEntries(
        eventId,
        entry("1-0", "SeatsHeld", 1L, HAPPENED_AT.minusSeconds(5)),
        entry("2-0", "SeatsHeld", 2L, HAPPENED_AT.minusSeconds(1)));

    relay.relayOnce();

    // The oldest of the batch and not the newest: the question the lag answers is how long the
    // change that has been waiting longest has waited.
    assertThat(lagSeconds()).isEqualTo(5.0);
  }

  @Test
  void reportsTheOldestEntryAcrossEverySale() {
    UUID first = anOpenSale();
    UUID second = anOpenSale();
    stream.hasEntries(first, entry("1-0", "SeatsHeld", 1L, HAPPENED_AT.minusSeconds(2)));
    stream.hasEntries(second, entry("1-0", "SeatsHeld", 1L, HAPPENED_AT.minusSeconds(9)));

    relay.relayOnce();

    assertThat(lagSeconds()).isEqualTo(9.0);
  }

  @Test
  void dropsTheLagToZeroOnceTheBacklogIsGone() {
    UUID eventId = anOpenSale();
    stream.hasEntries(eventId, entry("1-0", "SeatsHeld", 1L, HAPPENED_AT.minusSeconds(5)));
    relay.relayOnce();

    assertThat(relay.relayOnce()).isZero();
    assertThat(lagSeconds())
        .as("a pass that found every stream empty is the one thing that clears the lag")
        .isZero();
  }

  @Test
  void keepsReportingTheLagOfWhatItCouldNotPublish() {
    UUID eventId = anOpenSale();
    stream.hasEntries(eventId, entry("1-0", "SeatsHeld", 1L, HAPPENED_AT.minusSeconds(5)));
    publisher.alwaysThrows();

    relay.relay();

    // The pass threw, was swallowed by the schedule, and still reported the age of what it failed
    // to send: a gauge that only moved on success would sit frozen at its last healthy value for
    // the length of a broker outage, which is the one time anybody reads it.
    assertThat(lagSeconds()).isEqualTo(5.0);
  }

  @Test
  void leavesTheLastReadingAloneWhenAPassLearnedNothing() {
    UUID eventId = anOpenSale();
    stream.hasEntries(eventId, entry("1-0", "SeatsHeld", 1L, HAPPENED_AT.minusSeconds(5)));
    publisher.alwaysThrows();
    relay.relay();

    stream.cannotBeRead();
    relay.relay();

    assertThat(lagSeconds())
        .as("a catalog that could not be read is not a backlog that cleared")
        .isEqualTo(5.0);
  }

  @Test
  void publishesTheLagUnderTheSameNameAsThePostgresOutbox() {
    anOpenSale();
    relay.relayOnce();

    // One metric, two stores, which is what lets the alert rule of README section 14.4 be one
    // rule: the Postgres relay of libs/messaging publishes store="postgres" beside this.
    assertThat(meters.get("seatrush.outbox.lag.seconds").tag("store", "redis").gauge()).isNotNull();
  }

  private double lagSeconds() {
    return meters.get("seatrush.outbox.lag.seconds").tag("store", "redis").gauge().value();
  }

  private static SeatEventRecord entry(String recordId, String type, long version) {
    return entry(recordId, type, version, HAPPENED_AT);
  }

  private static SeatEventRecord entry(
      String recordId, String type, long version, Instant occurredAt) {
    return new SeatEventRecord(
        recordId,
        UUID.fromString("0199c5f0-2222-7000-8000-00000000b002"),
        type,
        List.of("A-1-1"),
        version,
        UUID.fromString("0199c5f0-4444-7000-8000-00000000d004"),
        occurredAt);
  }

  private UUID anOpenSale() {
    UUID eventId = catalogEvent();
    catalog.updateStatus(eventId, EventStatus.DRAFT, EventStatus.SCHEDULED);
    catalog.updateStatus(eventId, EventStatus.SCHEDULED, EventStatus.ON_SALE);
    return eventId;
  }

  private UUID catalogEvent() {
    UUID eventId = UUID.randomUUID();
    catalog.insert(
        Event.draft(eventId, "Final", HAPPENED_AT, HAPPENED_AT),
        List.of(new Section(eventId, "A", "Floor", Money.of(4500L, "EUR"))));
    return eventId;
  }

  /** A stream that hands out what a test put in it and remembers what was acknowledged. */
  private static final class RecordingStream implements SeatEventStream {

    private final Map<UUID, List<SeatEventRecord>> unread = new LinkedHashMap<>();
    private final Map<UUID, List<SeatEventRecord>> stale = new LinkedHashMap<>();
    private final Map<UUID, List<String>> acknowledged = new LinkedHashMap<>();
    private final Map<UUID, List<String>> delivered = new LinkedHashMap<>();
    private boolean unreadable;

    @Override
    public List<SeatEventRecord> read(UUID eventId, int count) {
      if (unreadable) {
        throw new IllegalStateException("the stream cannot be read");
      }
      List<SeatEventRecord> waiting = unread.getOrDefault(eventId, List.of());
      List<SeatEventRecord> taken =
          List.copyOf(waiting.subList(0, Math.min(count, waiting.size())));
      unread.put(eventId, new ArrayList<>(waiting.subList(taken.size(), waiting.size())));
      taken.forEach(
          record ->
              delivered.computeIfAbsent(eventId, id -> new ArrayList<>()).add(record.recordId()));
      return taken;
    }

    @Override
    public List<SeatEventRecord> claimStale(UUID eventId, Duration idleFor, int count) {
      List<SeatEventRecord> waiting = stale.getOrDefault(eventId, List.of());
      stale.put(eventId, List.of());
      waiting.forEach(
          record ->
              delivered.computeIfAbsent(eventId, id -> new ArrayList<>()).add(record.recordId()));
      return waiting;
    }

    @Override
    public void acknowledge(UUID eventId, List<String> recordIds) {
      acknowledged.computeIfAbsent(eventId, id -> new ArrayList<>()).addAll(recordIds);
    }

    @Override
    public long pendingCount(UUID eventId) {
      return delivered.getOrDefault(eventId, List.of()).size()
          - acknowledged.getOrDefault(eventId, List.of()).size();
    }

    void hasEntries(UUID eventId, SeatEventRecord... records) {
      unread.computeIfAbsent(eventId, id -> new ArrayList<>()).addAll(List.of(records));
    }

    /** A Redis that cannot be read at all, which is a pass that learns nothing. */
    void cannotBeRead() {
      unreadable = true;
    }

    void hasStaleEntries(UUID eventId, SeatEventRecord... records) {
      stale.computeIfAbsent(eventId, id -> new ArrayList<>()).addAll(List.of(records));
    }

    List<String> acknowledged(UUID eventId) {
      return acknowledged.getOrDefault(eventId, List.of());
    }
  }

  /** A publisher that can be told to fail, because that is the interesting half. */
  private static final class RecordingPublisher implements SeatEventPublisher {

    private final List<SeatEventRecord> published = new ArrayList<>();
    private int publishesBeforeFailing = Integer.MAX_VALUE;
    private boolean throwsInstead;

    @Override
    public List<String> publishAll(List<SeatEventRecord> records) {
      if (throwsInstead) {
        throw new IllegalStateException("the broker is unreachable");
      }
      List<String> acknowledged = new ArrayList<>();
      for (SeatEventRecord record : records) {
        if (published.size() >= publishesBeforeFailing) {
          break;
        }
        published.add(record);
        acknowledged.add(record.recordId());
      }
      return acknowledged;
    }

    void failsAfter(int records) {
      this.publishesBeforeFailing = records;
    }

    void alwaysThrows() {
      this.throwsInstead = true;
    }

    List<SeatEventRecord> published() {
      return published;
    }
  }
}
