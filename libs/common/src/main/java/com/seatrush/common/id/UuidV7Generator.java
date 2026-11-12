package com.seatrush.common.id;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;
import java.util.random.RandomGenerator;

/**
 * Default {@link IdGenerator}: UUIDv7 stamped with an injected {@link Clock}.
 *
 * <p>The clock is injected rather than read from {@code Instant.now()} so tests can mint ids at a
 * chosen instant. Random bits come from {@link ThreadLocalRandom} by default, which avoids the
 * contention a shared {@code SecureRandom} would create on the hold path: these bits make ids
 * unique, they are not a secret. Tokens and signing keys use the cryptographic sources in {@code
 * libs/security} instead.
 *
 * <p>The default generator is resolved per call, never cached in a field: a {@link
 * ThreadLocalRandom} instance belongs to the thread that asked for it, and this generator is a
 * singleton shared by every request thread.
 */
public final class UuidV7Generator implements IdGenerator {

  private final Clock clock;
  private final Supplier<RandomGenerator> randomSource;

  /** Uses the per-thread default source of random bits. */
  public UuidV7Generator(Clock clock) {
    this(clock, ThreadLocalRandom::current);
  }

  /** Uses a fixed source of random bits, which lets a test produce reproducible ids. */
  public UuidV7Generator(Clock clock, RandomGenerator random) {
    this(clock, fixedSource(random));
  }

  private UuidV7Generator(Clock clock, Supplier<RandomGenerator> randomSource) {
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.randomSource = randomSource;
  }

  @Override
  public UUID newId() {
    return UuidV7.from(clock.instant(), randomSource.get());
  }

  private static Supplier<RandomGenerator> fixedSource(RandomGenerator random) {
    Objects.requireNonNull(random, "random must not be null");
    return () -> random;
  }
}
