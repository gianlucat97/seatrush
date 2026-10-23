package com.seatrush.payment.adapters.in.messaging;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.MessageRouter;
import com.seatrush.messaging.messages.AuthorizePayment;
import com.seatrush.messaging.messages.CapturePayment;
import com.seatrush.messaging.messages.VoidPayment;
import com.seatrush.payment.application.PaymentService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The consumer of {@code payment.commands} (README section 10.1).
 *
 * <p>One consumer group for the whole deployment, not one per pod: these are commands, and exactly
 * one pod must perform each of them. That is the opposite of the broadcast pattern realtime uses
 * for seat events, and the difference is the group id.
 *
 * <p>Records keyed by {@code orderId} land on one partition, so the three commands of one checkout
 * are handled in the order they were sent. What goes wrong is handled by the policy of {@code
 * seatrush-messaging}: bounded retries, then the dead letter topic.
 */
@Component
public class PaymentCommandListener {

  private static final Logger log = LoggerFactory.getLogger(PaymentCommandListener.class);

  private final MessageRouter router;

  public PaymentCommandListener(PaymentService payments, MessageCodec codec) {
    this.router =
        MessageRouter.using(codec)
            .handle(AuthorizePayment.class, payments::authorize)
            .handle(CapturePayment.class, payments::capture)
            .handle(VoidPayment.class, payments::voidPayment)
            .build();
  }

  @KafkaListener(topics = Topics.PAYMENT_COMMANDS, groupId = "payment-service")
  public void onCommand(ConsumerRecord<String, String> record) {
    log.debug(
        "a payment command arrived for {} at offset {} of partition {}",
        record.key(),
        record.offset(),
        record.partition());
    router.route(record.value());
  }
}
