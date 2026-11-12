package com.seatrush.testsupport.time;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class MutableClockTest {

  private static final Instant NOON = Instant.parse("2026-06-01T12:00:00Z");

  @Nested
  @DisplayName("creation")
  class Creation {

    @Test
    void readsTheInstantItWasCreatedAt() {
      assertThat(MutableClock.at(NOON).instant()).isEqualTo(NOON);
    }

    @Test
    void defaultsToUtc() {
      assertThat(MutableClock.at(NOON).getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void acceptsAnExplicitZone() {
      ZoneId rome = ZoneId.of("Europe/Rome");
      assertThat(MutableClock.at(NOON, rome).getZone()).isEqualTo(rome);
    }

    @Test
    void theArbitraryInstantIsInUtcAndStable() {
      assertThat(MutableClock.atArbitraryInstant().instant())
          .isEqualTo(MutableClock.atArbitraryInstant().instant());
      assertThat(MutableClock.atArbitraryInstant().getZone()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    void rejectsNullArguments() {
      assertThatThrownBy(() -> MutableClock.at(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("instant");
      assertThatThrownBy(() -> MutableClock.at(NOON, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("zone");
    }
  }

  @Nested
  @DisplayName("moving")
  class Moving {

    @Test
    void advancesByTheGivenAmount() {
      MutableClock clock = MutableClock.at(NOON);

      Instant reached = clock.advanceBy(Duration.ofMinutes(5));

      assertThat(reached).isEqualTo(NOON.plus(Duration.ofMinutes(5)));
      assertThat(clock.instant()).isEqualTo(reached);
    }

    @Test
    void advancesCumulatively() {
      MutableClock clock = MutableClock.at(NOON);

      clock.advanceBy(Duration.ofSeconds(30));
      clock.advanceBy(Duration.ofSeconds(30));

      assertThat(clock.instant()).isEqualTo(NOON.plusSeconds(60));
    }

    @Test
    void movesBackOnANegativeAmount() {
      MutableClock clock = MutableClock.at(NOON);

      clock.advanceBy(Duration.ofMinutes(-1));

      assertThat(clock.instant()).isEqualTo(NOON.minusSeconds(60));
    }

    @Test
    void jumpsToAnArbitraryInstant() {
      MutableClock clock = MutableClock.at(NOON);

      clock.setTo(Instant.EPOCH);

      assertThat(clock.instant()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void rejectsNullArguments() {
      MutableClock clock = MutableClock.at(NOON);

      assertThatThrownBy(() -> clock.advanceBy(null)).isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> clock.setTo(null)).isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("zones")
  class Zones {

    @Test
    void aZonedViewFollowsTheClockItCameFrom() {
      MutableClock utc = MutableClock.at(NOON);
      MutableClock rome = utc.withZone(ZoneId.of("Europe/Rome"));

      utc.advanceBy(Duration.ofHours(2));

      assertThat(rome.instant()).isEqualTo(utc.instant());
    }

    @Test
    void theOriginalFollowsTheZonedViewToo() {
      MutableClock utc = MutableClock.at(NOON);
      MutableClock rome = utc.withZone(ZoneId.of("Europe/Rome"));

      rome.advanceBy(Duration.ofHours(2));

      assertThat(utc.instant()).isEqualTo(NOON.plusSeconds(7200));
    }

    @Test
    void theZoneIsTheOneCalendarQuestionsAreAnsweredIn() {
      // 00:30 UTC is already the next day in Rome, which is summer time in June.
      MutableClock clock = MutableClock.at(Instant.parse("2026-06-02T00:30:00Z"));

      assertThat(LocalDate.now(clock)).isEqualTo(LocalDate.of(2026, 6, 2));
      assertThat(LocalDate.now(clock.withZone(ZoneId.of("Europe/Rome"))))
          .isEqualTo(LocalDate.of(2026, 6, 2));
      assertThat(LocalDate.now(clock.withZone(ZoneId.of("America/New_York"))))
          .isEqualTo(LocalDate.of(2026, 6, 1));
    }

    @Test
    void askingForTheZoneItAlreadyHasReturnsTheSameClock() {
      MutableClock clock = MutableClock.at(NOON);

      assertThat(clock.withZone(ZoneOffset.UTC)).isSameAs(clock);
    }

    @Test
    void rejectsANullZone() {
      assertThatThrownBy(() -> MutableClock.at(NOON).withZone(null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("concurrent use")
  class ConcurrentUse {

    @Test
    void everyAdvanceIsCountedWhenManyThreadsMoveTheClockAtOnce() throws Exception {
      int threads = 1_000;
      MutableClock clock = MutableClock.at(NOON);

      List<Callable<Instant>> advances =
          IntStream.range(0, threads)
              .<Callable<Instant>>mapToObj(i -> () -> clock.advanceBy(Duration.ofSeconds(1)))
              .toList();

      List<Future<Instant>> results;
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        results = executor.invokeAll(advances);
      }

      for (Future<Instant> result : results) {
        result.get();
      }
      assertThat(clock.instant()).isEqualTo(NOON.plusSeconds(threads));
    }

    @Test
    void noReaderEverSeesAnInstantThatWasNeverSet() throws Exception {
      int readers = 500;
      MutableClock clock = MutableClock.at(NOON);

      List<Callable<Instant>> reads =
          IntStream.range(0, readers).<Callable<Instant>>mapToObj(i -> clock::instant).toList();

      List<Future<Instant>> results;
      try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
        Future<?> writer =
            executor.submit(
                () -> {
                  for (int i = 0; i < readers; i++) {
                    clock.advanceBy(Duration.ofSeconds(1));
                  }
                });
        results = executor.invokeAll(reads);
        writer.get();
      }

      for (Future<Instant> result : results) {
        assertThat(result.get()).isBetween(NOON, NOON.plusSeconds(readers));
      }
    }
  }
}
