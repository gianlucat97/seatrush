package com.seatrush.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.messaging.messages.EventSoldOut;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.OrderConfirmed;
import com.seatrush.messaging.messages.PaymentDeclined;
import com.seatrush.messaging.messages.SampleMessages;
import org.junit.jupiter.api.Test;

class TopicsTest {

  @Test
  void namesTheSixTopicsOfTheSpecification() {
    assertThat(Topics.all())
        .containsExactly(
            "inventory.seat-events",
            "inventory.commands",
            "inventory.replies",
            "payment.commands",
            "payment.replies",
            "order.events");
  }

  @Test
  void everyTopicHasOneDeadLetterTopic() {
    assertThat(Topics.allDlq())
        .hasSameSizeAs(Topics.all())
        .allSatisfy(dlq -> assertThat(dlq).endsWith(Topics.DLQ_SUFFIX))
        .contains("order.events.dlq");
    assertThat(Topics.dlqOf(Topics.PAYMENT_REPLIES)).isEqualTo("payment.replies.dlq");
  }

  @Test
  void aPoisonMessageOfADlqHasNowhereFurtherToGo() {
    assertThatThrownBy(() -> Topics.dlqOf("payment.replies.dlq"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a SeatRush business topic");
    assertThatThrownBy(() -> Topics.dlqOf("inventory.seat.events"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void routesEachGroupOfMessagesToItsTopic() {
    assertThat(Topics.of(SampleMessages.seatsHeld())).isEqualTo(Topics.INVENTORY_SEAT_EVENTS);
    assertThat(Topics.of(new EventSoldOut(SampleMessages.EVENT_ID)))
        .isEqualTo(Topics.INVENTORY_SEAT_EVENTS);
    assertThat(Topics.of(SampleMessages.commitSeats())).isEqualTo(Topics.INVENTORY_COMMANDS);
    assertThat(Topics.of(new HoldReleased(SampleMessages.ORDER_ID, SampleMessages.HOLD_ID)))
        .isEqualTo(Topics.INVENTORY_REPLIES);
    assertThat(Topics.of(SampleMessages.authorizePayment())).isEqualTo(Topics.PAYMENT_COMMANDS);
    assertThat(Topics.of(new PaymentDeclined(SampleMessages.ORDER_ID, "declined")))
        .isEqualTo(Topics.PAYMENT_REPLIES);
    assertThat(
            Topics.of(
                new OrderConfirmed(
                    SampleMessages.ORDER_ID, SampleMessages.USER_ID, SampleMessages.EVENT_ID)))
        .isEqualTo(Topics.ORDER_EVENTS);
  }

  @Test
  void everyTopicIsReachedByAtLeastOneMessageOfTheCatalog() {
    assertThat(SampleMessages.oneOfEach())
        .extracting(Topics::of)
        .containsAll(Topics.all())
        .allSatisfy(topic -> assertThat(Topics.all()).contains(topic));
  }

  @Test
  void seatEventsAreKeyedByEventAndEverythingElseByOrder() {
    for (Message message : SampleMessages.oneOfEach()) {
      boolean isSeatEvent = Topics.of(message).equals(Topics.INVENTORY_SEAT_EVENTS);
      assertThat(message.partitionKey())
          .as("key of %s", message.type())
          .isEqualTo(isSeatEvent ? SampleMessages.EVENT_ID : SampleMessages.ORDER_ID);
    }
  }
}
