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
  private final SeatEventRelay relay =
      new SeatEventRelay(
          catalog,
          stream,
          publisher,
          new StreamRelayProperties(Duration.ofMillis(100), 10, Duration.ofSeconds(30)),
          new SimpleMeterRegistry());

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

  private static SeatEventRecord entry(String recordId, String type, long version) {
    return new SeatEventRecord(
        recordId,
        UUID.fromString("0199c5f0-2222-7000-8000-00000000b002"),
        type,
        List.of("A-1-1"),
        version,
        UUID.fromString("0199c5f0-4444-7000-8000-00000000d004"),
        HAPPENED_AT);
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

    @Override
    public List<SeatEventRecord> read(UUID eventId, int count) {
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
