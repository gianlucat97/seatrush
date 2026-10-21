package com.seatrush.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UuidV7GeneratorTest {

  private static final Instant ON_SALE = Instant.parse("2026-10-21T19:07:38Z");

  @Test
  void stampsIdsWithTheInjectedClock() {
    IdGenerator generator = new UuidV7Generator(Clock.fixed(ON_SALE, ZoneOffset.UTC));

    assertThat(UuidV7.timestampOf(generator.newId())).isEqualTo(ON_SALE);
  }

  @Test
  void producesDistinctIdsAtTheSameInstant() {
    IdGenerator generator = new UuidV7Generator(Clock.fixed(ON_SALE, ZoneOffset.UTC));

    Set<UUID> ids =
        IntStream.range(0, 10_000).mapToObj(i -> generator.newId()).collect(Collectors.toSet());

    assertThat(ids).hasSize(10_000);
  }

  @Test
  void producesIdsThatSortInCreationOrder() {
    List<UUID> ids =
        IntStream.range(0, 50)
            .mapToObj(
                i ->
                    new UuidV7Generator(Clock.fixed(ON_SALE.plusMillis(i), ZoneOffset.UTC)).newId())
            .toList();

    assertThat(ids).isSortedAccordingTo(Comparator.comparing(UUID::toString));
  }

  @Test
  void staysUniqueUnderManyConcurrentVirtualThreads() throws Exception {
    IdGenerator generator = new UuidV7Generator(Clock.fixed(ON_SALE, ZoneOffset.UTC));
    int threads = 10_000;
    Set<UUID> ids = ConcurrentHashMap.newKeySet();
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(threads);

    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      IntStream.range(0, threads)
          .forEach(
              i ->
                  executor.submit(
                      () -> {
                        start.await();
                        ids.add(generator.newId());
                        done.countDown();
                        return null;
                      }));
      start.countDown();
      assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(ids).hasSize(threads);
  }

  @Test
  void rejectsMissingCollaborators() {
    assertThatNullPointerException().isThrownBy(() -> new UuidV7Generator(null));
    assertThatNullPointerException().isThrownBy(() -> new UuidV7Generator(Clock.systemUTC(), null));
  }
}
