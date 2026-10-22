package com.seatrush.messaging.messages;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.money.Money;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** What the message records refuse to be built with, so a bad payload never reaches a topic. */
class MessagePayloadsTest {

  private static final UUID ORDER_ID = SampleMessages.ORDER_ID;
  private static final UUID EVENT_ID = SampleMessages.EVENT_ID;
  private static final UUID HOLD_ID = SampleMessages.HOLD_ID;
  private static final List<String> SEATS = SampleMessages.SEAT_IDS;

  @Nested
  @DisplayName("identifiers")
  class Identifiers {

    @Test
    void everyIdOfAPayloadIsRequired() {
      assertThatThrownBy(() -> new CommitSeats(null, EVENT_ID, HOLD_ID, SEATS))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("orderId");
      assertThatThrownBy(() -> new CommitSeats(ORDER_ID, null, HOLD_ID, SEATS))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("eventId");
      assertThatThrownBy(() -> new CommitSeats(ORDER_ID, EVENT_ID, null, SEATS))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("holdId");
      assertThatThrownBy(() -> new EventSoldOut(null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("eventId");
      assertThatThrownBy(() -> new PaymentAuthorized(ORDER_ID, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("paymentOperationId");
    }
  }

  @Nested
  @DisplayName("seat ids")
  class SeatIds {

    @Test
    void aMessageAboutSeatsCarriesAtLeastOne() {
      assertThatThrownBy(() -> new CommitSeats(ORDER_ID, EVENT_ID, HOLD_ID, List.of()))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seatIds must not be empty");
      assertThatThrownBy(() -> new SeatsHeld(EVENT_ID, List.of(), 1L, HOLD_ID))
          .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBlankOrMissingSeatIdIsRefused() {
      assertThatThrownBy(() -> new SeatsSold(EVENT_ID, List.of(" "), 1L, HOLD_ID))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("seatId");
      assertThatThrownBy(() -> new SeatsSold(EVENT_ID, Arrays.asList("A-1-1", null), 1L, HOLD_ID))
          .isInstanceOf(NullPointerException.class);
      assertThatThrownBy(() -> new SeatsSold(EVENT_ID, null, 1L, HOLD_ID))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("seatIds");
    }

    @Test
    void theListCannotBeChangedThroughTheCallerThatPassedIt() {
      List<String> mutable = new ArrayList<>(SEATS);
      SeatsCommitted committed = new SeatsCommitted(ORDER_ID, EVENT_ID, mutable);

      mutable.add("B-1-1");

      assertThat(committed.seatIds()).containsExactlyElementsOf(SEATS);
      assertThatThrownBy(() -> committed.seatIds().add("B-1-1"))
          .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void aFailureMayConflictWithNoSeatInParticular() {
      SeatsCommitFailed failed =
          new SeatsCommitFailed(ORDER_ID, EVENT_ID, "hold is no longer in checkout", List.of());

      assertThat(failed.conflictingSeatIds()).isEmpty();
      assertThatThrownBy(() -> new SeatsCommitFailed(ORDER_ID, EVENT_ID, "reason", null))
          .isInstanceOf(NullPointerException.class);
    }
  }

  @Nested
  @DisplayName("seat state version")
  class Version {

    @Test
    void aSeatEventCarriesTheVersionItWasProducedAt() {
      assertThat(new SeatsHeld(EVENT_ID, SEATS, 42L, HOLD_ID).version()).isEqualTo(42L);
    }

    @Test
    void aVersionNobodyIncrementedIsRefused() {
      assertThatThrownBy(() -> new SeatsHeld(EVENT_ID, SEATS, 0L, HOLD_ID))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("version must be positive");
      assertThatThrownBy(() -> new SeatsReleased(EVENT_ID, SEATS, -1L, HOLD_ID))
          .isInstanceOf(IllegalArgumentException.class);
    }
  }

  @Nested
  @DisplayName("reasons")
  class Reasons {

    @Test
    void aFreeTextReasonHasToSaySomething() {
      assertThatThrownBy(() -> new PaymentDeclined(ORDER_ID, " "))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("reason must not be blank");
      assertThatThrownBy(() -> new SeatsCommitFailed(ORDER_ID, EVENT_ID, null, SEATS))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("reason");
    }

    @Test
    void aFailedOrderCarriesOneOfTheThreeSpecifiedReasons() {
      assertThat(FailureReason.values())
          .containsExactly(
              FailureReason.PAYMENT_DECLINED,
              FailureReason.SEATS_UNAVAILABLE,
              FailureReason.TIMEOUT);
      assertThatThrownBy(() -> new OrderFailed(ORDER_ID, SampleMessages.USER_ID, EVENT_ID, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("failureReason");
    }
  }

  @Nested
  @DisplayName("amounts")
  class Amounts {

    @Test
    void anAuthorizationIsForARealAmount() {
      assertThatThrownBy(
              () ->
                  new AuthorizePayment(
                      ORDER_ID, SampleMessages.USER_ID, Money.zero(Currency.getInstance("EUR"))))
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("amount must not be zero");
      assertThatThrownBy(() -> new AuthorizePayment(ORDER_ID, SampleMessages.USER_ID, null))
          .isInstanceOf(NullPointerException.class)
          .hasMessageContaining("amount");
    }

    @Test
    void theAmountKeepsTheCurrencyItWasCreatedWith() {
      AuthorizePayment command =
          new AuthorizePayment(ORDER_ID, SampleMessages.USER_ID, Money.of(12_000L, "EUR"));

      assertThat(command.amount().amountCents()).isEqualTo(12_000L);
      assertThat(command.amount().currency()).isEqualTo(Currency.getInstance("EUR"));
    }
  }
}
