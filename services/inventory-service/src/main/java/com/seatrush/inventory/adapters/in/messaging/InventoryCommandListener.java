package com.seatrush.inventory.adapters.in.messaging;

import com.seatrush.inventory.application.CheckoutCommandService;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.MessageRouter;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.ReleaseHold;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * The consumer of {@code inventory.commands} (README section 10.1).
 *
 * <p>One consumer group for the whole deployment, not one per pod: these are commands, and exactly
 * one pod must perform each of them. That is the opposite of the broadcast pattern realtime will
 * use for seat events, and the difference is the group id.
 *
 * <p>Records keyed by {@code orderId} land on one partition, so a commit and the release that
 * compensates it cannot be handled out of order.
 */
@Component
public class InventoryCommandListener {

  private static final Logger log = LoggerFactory.getLogger(InventoryCommandListener.class);

  private final MessageRouter router;

  public InventoryCommandListener(CheckoutCommandService commands, MessageCodec codec) {
    this.router =
        MessageRouter.using(codec)
            .handle(CommitSeats.class, commands::commit)
            .handle(ReleaseHold.class, commands::release)
            .build();
  }

  @KafkaListener(topics = Topics.INVENTORY_COMMANDS, groupId = "inventory-service")
  public void onCommand(ConsumerRecord<String, String> record) {
    log.debug(
        "an inventory command arrived for {} at offset {} of partition {}",
        record.key(),
        record.offset(),
        record.partition());
    router.route(record.value());
  }
}
