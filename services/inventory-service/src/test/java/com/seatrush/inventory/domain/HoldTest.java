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
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
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
              () ->
                  new Hold(
                      HOLD_ID, EVENT_ID, USER_ID, List.of(), EXPIRES_AT, HoldStatus.ACTIVE, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("at least one seat");
    }

    @Test
    void refusesToBeBuiltWithoutWhatItNeeds() {
      assertThatThrownBy(
              () ->
                  new Hold(
                      HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1"), null, HoldStatus.ACTIVE, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("expiresAt");
      assertThatThrownBy(
              () ->
                  new Hold(
                      HOLD_ID,
                      EVENT_ID,
                      null,
                      List.of("A-1-1"),
                      EXPIRES_AT,
                      HoldStatus.ACTIVE,
                      null))
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

  @Nested
  @DisplayName("the order it belongs to")
  class TheOrder {

    @Test
    void isAbsentWhileNoCheckoutHasTakenIt() {
      assertThat(hold(HoldStatus.ACTIVE).order()).isEmpty();
    }

    @Test
    void isTheOrderThatFrozeIt() {
      assertThat(hold(HoldStatus.CHECKOUT).order()).contains(ORDER_ID);
      assertThat(hold(HoldStatus.CONSUMED).order()).contains(ORDER_ID);
    }

    /**
     * A hold in either status belongs to an order by definition, and the commit procedure of README
     * section 9.6 checks which one before it sells anything.
     */
    @ParameterizedTest
    @EnumSource(
        value = HoldStatus.class,
        names = {"CHECKOUT", "CONSUMED"})
    void isRequiredByTheTwoStatusesThatHaveOne(HoldStatus status) {
      assertThatThrownBy(
              () ->
                  new Hold(HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1"), EXPIRES_AT, status, null))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("belongs to an order");
    }

    /**
     * It survives a release, so a hold a compensation gave back still says which checkout had it.
     */
    @Test
    void survivesARelease() {
      Hold released =
          new Hold(
              HOLD_ID,
              EVENT_ID,
              USER_ID,
              List.of("A-1-1"),
              EXPIRES_AT,
              HoldStatus.RELEASED,
              ORDER_ID);

      assertThat(released.order()).contains(ORDER_ID);
    }

    @Test
    void knowsWhetherItIsFrozenForAnOrder() {
      assertThat(hold(HoldStatus.CHECKOUT).isFrozenFor(ORDER_ID)).isTrue();
      assertThat(hold(HoldStatus.CHECKOUT).isFrozenFor(UUID.randomUUID())).isFalse();
      assertThat(hold(HoldStatus.ACTIVE).isFrozenFor(ORDER_ID))
          .as("a hold nobody froze is nobody's to commit")
          .isFalse();
      assertThat(hold(HoldStatus.CONSUMED).isFrozenFor(ORDER_ID))
          .as("and one already sold is past being frozen")
          .isFalse();
    }

    @Test
    void knowsWhetherItWasAlreadySoldToAnOrder() {
      assertThat(hold(HoldStatus.CONSUMED).isSoldTo(ORDER_ID)).isTrue();
      assertThat(hold(HoldStatus.CONSUMED).isSoldTo(UUID.randomUUID())).isFalse();
      assertThat(hold(HoldStatus.CHECKOUT).isSoldTo(ORDER_ID)).isFalse();
      assertThat(hold(HoldStatus.ACTIVE).isSoldTo(ORDER_ID)).isFalse();
    }
  }

  private static Hold hold(HoldStatus status) {
    // A checkout and a consumed hold belong to an order; the others belong to none.
    UUID orderId = status == HoldStatus.CHECKOUT || status == HoldStatus.CONSUMED ? ORDER_ID : null;
    return new Hold(
        HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1", "A-1-2"), EXPIRES_AT, status, orderId);
  }
}
