package com.seatrush.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class HoldTest {

  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T10:05:00Z");

  @Nested
  @DisplayName("the hold itself")
  class TheHold {

    @Test
    void knowsItsSeatsAndItsOwner() {
      Hold hold = hold(HoldStatus.ACTIVE);

      assertThat(hold.seatCount()).isEqualTo(2);
      assertThat(hold.belongsTo(USER_ID)).isTrue();
      assertThat(hold.belongsTo(UUID.randomUUID())).isFalse();
    }

    @Test
    void refusesToHoldNothing() {
      assertThatThrownBy(
              () -> new Hold(HOLD_ID, EVENT_ID, USER_ID, List.of(), EXPIRES_AT, HoldStatus.ACTIVE))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one seat");
    }

    @Test
    void refusesToBeBuiltWithoutWhatItNeeds() {
      assertThatThrownBy(
              () -> new Hold(HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1"), null, HoldStatus.ACTIVE))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("expiresAt");
      assertThatThrownBy(
              () ->
                  new Hold(
                      HOLD_ID, EVENT_ID, null, List.of("A-1-1"), EXPIRES_AT, HoldStatus.ACTIVE))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("userId");
    }
  }

  @Nested
  @DisplayName("being due")
  class BeingDue {

    @Test
    void anActiveHoldIsDueOnceItsInstantArrives() {
      Hold hold = hold(HoldStatus.ACTIVE);

      assertThat(hold.isDue(EXPIRES_AT.minus(Duration.ofSeconds(1)))).isFalse();
      assertThat(hold.isDue(EXPIRES_AT)).isTrue();
      assertThat(hold.isDue(EXPIRES_AT.plus(Duration.ofMinutes(5)))).isTrue();
    }

    @Test
    void aFrozenHoldIsNeverDue() {
      assertThat(hold(HoldStatus.CHECKOUT).isDue(EXPIRES_AT.plus(Duration.ofHours(1))))
          .as("the SAGA owns a frozen hold and always ends it")
          .isFalse();
    }

    @ParameterizedTest
    @EnumSource(HoldStatus.class)
    void onlyAnActiveHoldCanBeSwept(HoldStatus status) {
      assertThat(hold(status).isDue(EXPIRES_AT.plus(Duration.ofHours(1))))
          .isEqualTo(status == HoldStatus.ACTIVE);
    }
  }

  @Nested
  @DisplayName("statuses")
  class Statuses {

    @Test
    void aHoldOwnsItsSeatsWhileItIsActiveOrFrozen() {
      assertThat(HoldStatus.ACTIVE.holdsSeats()).isTrue();
      assertThat(HoldStatus.CHECKOUT.holdsSeats()).isTrue();
      assertThat(HoldStatus.CONSUMED.holdsSeats()).isFalse();
      assertThat(HoldStatus.RELEASED.holdsSeats()).isFalse();
      assertThat(HoldStatus.EXPIRED.holdsSeats()).isFalse();
    }

    @Test
    void onlyAnActiveHoldIsExpirable() {
      assertThat(HoldStatus.ACTIVE.isExpirable()).isTrue();
      assertThat(HoldStatus.CHECKOUT.isExpirable()).isFalse();
    }

    @Test
    void theStatusesAreTheOnesTheSpecificationLists() {
      assertThat(HoldStatus.values())
          .containsExactly(
              HoldStatus.ACTIVE,
              HoldStatus.CHECKOUT,
              HoldStatus.CONSUMED,
              HoldStatus.RELEASED,
              HoldStatus.EXPIRED);
    }
  }

  @Nested
  @DisplayName("a hold request")
  class Requests {

    private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");

    @Test
    void itsFingerprintIgnoresTheOrderOfTheSeats() {
      assertThat(request(List.of("A-1-2", "A-1-1")).fingerprint())
          .isEqualTo(request(List.of("A-1-1", "A-1-2")).fingerprint());
    }

    @Test
    void differentSeatsAreADifferentRequest() {
      assertThat(request(List.of("A-1-1")).fingerprint())
          .isNotEqualTo(request(List.of("A-1-2")).fingerprint());
    }

    @Test
    void refusesTheSameSeatTwice() {
      assertThatThrownBy(() -> request(List.of("A-1-1", "A-1-1")))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("requested twice");
    }

    @Test
    void refusesAHoldThatWouldExpireBeforeItIsTaken() {
      assertThatThrownBy(
              () -> new HoldRequest(EVENT_ID, HOLD_ID, USER_ID, List.of("A-1-1"), "key", NOW, NOW))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("must expire after");
    }

    @Test
    void refusesAnEmptyIdempotencyKey() {
      assertThatThrownBy(
              () ->
                  new HoldRequest(
                      EVENT_ID, HOLD_ID, USER_ID, List.of("A-1-1"), " ", NOW, NOW.plusSeconds(60)))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("idempotencyKey");
    }

    private static HoldRequest request(List<String> seatIds) {
      return new HoldRequest(
          EVENT_ID, HOLD_ID, USER_ID, seatIds, "idempotency-key", NOW, NOW.plusSeconds(300));
    }
  }

  private static Hold hold(HoldStatus status) {
    return new Hold(HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1", "A-1-2"), EXPIRES_AT, status);
  }
}
