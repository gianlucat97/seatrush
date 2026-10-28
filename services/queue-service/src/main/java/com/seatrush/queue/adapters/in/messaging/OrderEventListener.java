package com.seatrush.queue.adapters.in.messaging;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.MessageRouter;
import com.seatrush.messaging.messages.OrderConfirmed;
import com.seatrush.messaging.messages.OrderFailed;
import com.seatrush.queue.application.QueueService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The end of a checkout, which is the end of somebody's turn in the purchase area (README sections
 * 6.2 and 10.1).
 *
 * <p>Both outcomes do the same thing here, and that is the point: a confirmed order and a failed
 * one are opposite news for the buyer and the same news for the waiting room, because either way
 * the room they were let into is free. The reason a failure carries is for the client that shows
 * it, not for this.
 *
 * <p>One consumer group for the whole deployment. A slot is given back once, and these records are
 * keyed by {@code orderId}, so the two events of one checkout cannot be handled out of order by two
 * pods. This is the opposite of the broadcast pattern realtime uses, where every pod needs every
 * seat event because every pod has its own clients.
 *
 * <p>Nothing guards against a redelivery, and nothing has to. This service owns no database, so
 * there is no {@code processed_message} table to claim a message in; what makes that safe is that
 * the work itself is idempotent, since removing a member from a sorted set twice removes it once. A
 * message about a buyer whose admission has already expired finds nothing to remove either, which
 * is the same answer and also the right one.
 */
@Component
public class OrderEventListener {

  private static final Logger log = LoggerFactory.getLogger(OrderEventListener.class);

  private final MessageRouter router;

  public OrderEventListener(QueueService queue, MessageCodec codec) {
    this.router =
        MessageRouter.using(codec)
            .handle(
                OrderConfirmed.class,
                envelope ->
                    queue.releaseSlot(envelope.payload().eventId(), envelope.payload().userId()))
            .handle(
                OrderFailed.class,
                envelope ->
                    queue.releaseSlot(envelope.payload().eventId(), envelope.payload().userId()))
            .build();
  }

  @KafkaListener(topics = Topics.ORDER_EVENTS, groupId = "queue-service")
  public void onOrderEvent(ConsumerRecord<String, String> record) {
    log.debug("an order of {} ended, at offset {}", record.key(), record.offset());
    router.route(record.value());
  }
}
