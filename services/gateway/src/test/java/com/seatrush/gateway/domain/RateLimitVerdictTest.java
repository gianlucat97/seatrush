package com.seatrush.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/**
 * The arithmetic of a refusal, which is what a client is told to do about it.
 *
 * <p>Two things are worth getting right here and neither is obvious. The wait a client is handed
 * can never round down to zero, because a {@code Retry-After} of zero is an invitation to retry
 * immediately and the refusal exists to stop exactly that; and combining the two buckets of one
 * request has to take the longer wait, because a client told the shorter one comes back and is
 * refused by the other bucket.
 */
class RateLimitVerdictTest {

  @Test
  void anAllowedRequestWaitsForNothing() {
    assertThat(RateLimitVerdict.ALLOWED.allowed()).isTrue();
    assertThat(RateLimitVerdict.ALLOWED.retryAfter()).isZero();
  }

  @Test
  void aRefusalCarriesItsWait() {
    RateLimitVerdict refused = RateLimitVerdict.refused(Duration.ofMillis(1_500));

    assertThat(refused.allowed()).isFalse();
    assertThat(refused.retryAfter()).isEqualTo(Duration.ofMillis(1_500));
  }

  @Test
  void roundsTheWaitUpToWholeSeconds() {
    assertThat(RateLimitVerdict.refused(Duration.ofMillis(1_001)).retryAfterSeconds()).isEqualTo(2);
    assertThat(RateLimitVerdict.refused(Duration.ofMillis(2_000)).retryAfterSeconds()).isEqualTo(2);
  }

  /** The case that matters: a sub-second wait is still a wait. */
  @Test
  void neverTellsAClientToComeBackAtOnce() {
    assertThat(RateLimitVerdict.refused(Duration.ofMillis(1)).retryAfterSeconds()).isEqualTo(1);
    assertThat(RateLimitVerdict.refused(Duration.ZERO).retryAfterSeconds()).isEqualTo(1);
  }

  @Test
  void twoAllowedBucketsAreAnAllowedRequest() {
    assertThat(RateLimitVerdict.ALLOWED.and(RateLimitVerdict.ALLOWED))
        .isEqualTo(RateLimitVerdict.ALLOWED);
  }

  @Test
  void eitherBucketRefusingIsARefusal() {
    RateLimitVerdict refused = RateLimitVerdict.refused(Duration.ofSeconds(3));

    assertThat(RateLimitVerdict.ALLOWED.and(refused).allowed()).isFalse();
    assertThat(refused.and(RateLimitVerdict.ALLOWED).allowed()).isFalse();
  }

  @Test
  void aRefusalByBothTakesTheLongerWait() {
    RateLimitVerdict user = RateLimitVerdict.refused(Duration.ofSeconds(2));
    RateLimitVerdict address = RateLimitVerdict.refused(Duration.ofSeconds(7));

    assertThat(user.and(address).retryAfter()).isEqualTo(Duration.ofSeconds(7));
    assertThat(address.and(user).retryAfter()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void aRefusalByOneTakesItsWaitAndNotTheOtherZero() {
    RateLimitVerdict refused = RateLimitVerdict.refused(Duration.ofSeconds(4));

    assertThat(RateLimitVerdict.ALLOWED.and(refused).retryAfter()).isEqualTo(Duration.ofSeconds(4));
    assertThat(refused.and(RateLimitVerdict.ALLOWED).retryAfter()).isEqualTo(Duration.ofSeconds(4));
  }

  @Test
  void combiningIsAssociativeEnoughToOrderTheBucketsEitherWay() {
    RateLimitVerdict first = RateLimitVerdict.refused(Duration.ofSeconds(1));
    RateLimitVerdict second = RateLimitVerdict.refused(Duration.ofSeconds(9));

    assertThat(first.and(second)).isEqualTo(second.and(first));
  }

  @Test
  void refusesToBeBuiltOutOfAContradiction() {
    assertThatThrownBy(() -> new RateLimitVerdict(true, Duration.ofSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nothing to wait for");
  }

  @Test
  void refusesANegativeOrMissingWait() {
    assertThatThrownBy(() -> new RateLimitVerdict(false, Duration.ofSeconds(-1)))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RateLimitVerdict(false, null))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
