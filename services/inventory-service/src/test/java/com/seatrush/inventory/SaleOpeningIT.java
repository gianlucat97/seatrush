package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.SaleOpener;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.SeatStateStore;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * A sale opening on its own clock, against a real Postgres and a real Redis.
 *
 * <p>What only this test can show is the part the unit test mocks away: that the query really does
 * select on {@code on_sale_at} and the status together, that the row really is claimed for the
 * transaction, and that when the status has moved the seat map is already there. The last one is
 * the whole promise, because a buyer arriving a millisecond after the doors open must not find a
 * sale with nothing behind it.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Driven by hand: a pass firing between an act and an assertion would make the result a race.
      "seatrush.sale-opener.interval=1h"
    })
@EnabledIfDockerAvailable
class SaleOpeningIT {

  private static final String DATABASE = "inventory_sale_opening_it";
  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

  @Autowired private EventCatalogService catalog;
  @Autowired private SaleOpener opener;
  @Autowired private SeatStateStore seatState;
  @Autowired private MutableClock clock;
  @Autowired private JdbcClient jdbc;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    RedisSupport.registerProperties(registry);
  }

  @AfterEach
  void emptyTheCatalog() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event CASCADE").update();
    RedisSupport.flushAll();
  }

  @Test
  void aScheduledSaleOpensItselfWhenItsMomentComes() {
    clock.setTo(NOW);
    UUID eventId = scheduledFor(NOW.plus(Duration.ofMinutes(2)));

    assertThat(opener.openOnce()).isZero();
    assertThat(statusOf(eventId)).isEqualTo(EventStatus.SCHEDULED);

    clock.advanceBy(Duration.ofMinutes(2));

    assertThat(opener.openOnce()).isEqualTo(1);
    assertThat(statusOf(eventId)).isEqualTo(EventStatus.ON_SALE);
  }

  /** The order of README section 6.3, seen from the only place it matters. */
  @Test
  void theSeatMapIsInRedisByTheTimeTheStatusSaysOnSale() {
    clock.setTo(NOW);
    UUID eventId = scheduledFor(NOW);

    assertThat(seatState.read(eventId)).isEmpty();

    opener.openOnce();

    assertThat(statusOf(eventId)).isEqualTo(EventStatus.ON_SALE);
    assertThat(seatState.read(eventId)).isPresent();
    assertThat(seatState.read(eventId).orElseThrow().statusOf("A-1-1")).isNotNull();
  }

  @Test
  void aSaleStillInTheFutureIsLeftAlone() {
    clock.setTo(NOW);
    UUID eventId = scheduledFor(NOW.plus(Duration.ofSeconds(1)));

    assertThat(opener.openOnce()).isZero();
    assertThat(statusOf(eventId)).isEqualTo(EventStatus.SCHEDULED);
    assertThat(seatState.read(eventId)).isEmpty();
  }

  /** A draft is not announced, so its {@code onSaleAt} means nothing yet. */
  @Test
  void aDraftIsNeverOpenedHoweverOldItIs() {
    clock.setTo(NOW);
    UUID eventId =
        catalog
            .create(new CreateEventCommand("Unannounced", NOW.minus(Duration.ofDays(1)), venue()))
            .id();

    assertThat(opener.openOnce()).isZero();
    assertThat(statusOf(eventId)).isEqualTo(EventStatus.DRAFT);
  }

  @Test
  void aSaleAlreadyOpenIsNotOpenedTwice() {
    clock.setTo(NOW);
    UUID eventId = scheduledFor(NOW);
    opener.openOnce();

    assertThat(opener.openOnce()).isZero();
    assertThat(statusOf(eventId)).isEqualTo(EventStatus.ON_SALE);
  }

  @Test
  void everySaleThatIsDueOpensInOnePass() {
    clock.setTo(NOW);
    UUID first = scheduledFor(NOW.minus(Duration.ofMinutes(5)));
    UUID second = scheduledFor(NOW);

    assertThat(opener.openOnce()).isEqualTo(2);
    assertThat(statusOf(first)).isEqualTo(EventStatus.ON_SALE);
    assertThat(statusOf(second)).isEqualTo(EventStatus.ON_SALE);
  }

  private UUID scheduledFor(Instant onSaleAt) {
    UUID eventId =
        catalog.create(new CreateEventCommand("The Midnight Set", onSaleAt, venue())).id();
    catalog.schedule(eventId);
    return eventId;
  }

  private static List<SectionLayout> venue() {
    return List.of(new SectionLayout("A", "Stalls", Money.of(5000, "EUR"), 2, 10));
  }

  /** The clock the service reads, so a test can move time instead of waiting for it. */
  @TestConfiguration
  static class MovableTime {

    @Bean
    Clock clock() {
      return MutableClock.at(NOW);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }

  private EventStatus statusOf(UUID eventId) {
    return EventStatus.valueOf(
        jdbc.sql("SELECT status FROM event WHERE id = :id")
            .param("id", eventId)
            .query(String.class)
            .single());
  }
}
