package com.seatrush.order.adapters.in.messaging;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.MessageRouter;
import com.seatrush.messaging.messages.HoldReleased;
import com.seatrush.messaging.messages.PaymentAuthorized;
import com.seatrush.messaging.messages.PaymentCaptured;
import com.seatrush.messaging.messages.PaymentDeclined;
import com.seatrush.messaging.messages.PaymentVoided;
import com.seatrush.messaging.messages.SeatsCommitFailed;
import com.seatrush.messaging.messages.SeatsCommitted;
import com.seatrush.order.application.CheckoutSaga;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The replies that advance a checkout, from both services it talks to (README section 10.1).
 *
 * <p>Two topics and one router, because what a reply does depends on its type and not on where it
 * came from: the SAGA is one state machine, and splitting it by topic would mean two handlers
 * deciding whether a checkout may move.
 *
 * <p>One consumer group for the whole deployment, not one per pod: a reply advances a SAGA once.
 * Records keyed by {@code orderId} land on one partition, so the replies of one checkout arrive in
 * the order they were sent, which is why the state machine can expect a step rather than reconcile
 * an arbitrary interleaving.
 */
@Component
public class SagaReplyListener {

  private static final Logger log = LoggerFactory.getLogger(SagaReplyListener.class);

  private final MessageRouter router;

  public SagaReplyListener(CheckoutSaga saga, MessageCodec codec) {
    this.router =
        MessageRouter.using(codec)
            .handle(PaymentAuthorized.class, saga::onPaymentAuthorized)
            .handle(PaymentDeclined.class, saga::onPaymentDeclined)
            .handle(PaymentCaptured.class, saga::onPaymentCaptured)
            .handle(PaymentVoided.class, saga::onPaymentVoided)
            .handle(SeatsCommitted.class, saga::onSeatsCommitted)
            .handle(SeatsCommitFailed.class, saga::onSeatsCommitFailed)
            .handle(HoldReleased.class, saga::onHoldReleased)
            .build();
  }

  @KafkaListener(
      topics = {Topics.PAYMENT_REPLIES, Topics.INVENTORY_REPLIES},
      groupId = "order-service")
  public void onReply(ConsumerRecord<String, String> record) {
    log.debug(
        "a reply arrived for {} from {} at offset {}",
        record.key(),
        record.topic(),
        record.offset());
    router.route(record.value());
  }
}
