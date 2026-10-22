package com.seatrush.messaging;

import com.seatrush.messaging.messages.InventoryCommand;
import com.seatrush.messaging.messages.InventoryReply;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.OrderEvent;
import com.seatrush.messaging.messages.PaymentCommand;
import com.seatrush.messaging.messages.PaymentReply;
import com.seatrush.messaging.messages.SeatEvent;
import java.util.List;

/**
 * The Kafka topics of README section 10.1 and the topic each message belongs to.
 *
 * <p>Names live here and not in a property, because a topic name is part of the contract between
 * two services: a typo in a configuration file would create nothing (auto-creation is off on the
 * broker) and fail at runtime, while a typo here does not compile.
 *
 * <p>The partition count is not expressed here. It is a property of the topic the broker already
 * holds, set when the topic is created by {@code deploy/docker-compose/kafka/create-topics.sh}
 * locally and by the Helm hook on Kubernetes.
 */
public final class Topics {

  /** Seat map changes of one event, keyed by {@code eventId}. */
  public static final String INVENTORY_SEAT_EVENTS = "inventory.seat-events";

  /** Commands from order-service to inventory-service, keyed by {@code orderId}. */
  public static final String INVENTORY_COMMANDS = "inventory.commands";

  /** Replies from inventory-service to order-service, keyed by {@code orderId}. */
  public static final String INVENTORY_REPLIES = "inventory.replies";

  /** Commands from order-service to payment-service, keyed by {@code orderId}. */
  public static final String PAYMENT_COMMANDS = "payment.commands";

  /** Replies from payment-service to order-service, keyed by {@code orderId}. */
  public static final String PAYMENT_REPLIES = "payment.replies";

  /** Checkout outcomes, keyed by {@code orderId}. */
  public static final String ORDER_EVENTS = "order.events";

  /** The suffix of the dead letter topic that accompanies every topic above. */
  public static final String DLQ_SUFFIX = ".dlq";

  private static final List<String> ALL =
      List.of(
          INVENTORY_SEAT_EVENTS,
          INVENTORY_COMMANDS,
          INVENTORY_REPLIES,
          PAYMENT_COMMANDS,
          PAYMENT_REPLIES,
          ORDER_EVENTS);

  private Topics() {}

  /** Every business topic, in the order of the table of section 10.1. */
  public static List<String> all() {
    return ALL;
  }

  /** Every dead letter topic, one per business topic. */
  public static List<String> allDlq() {
    return ALL.stream().map(Topics::dlqOf).toList();
  }

  /**
   * The dead letter topic of a business topic.
   *
   * @throws IllegalArgumentException if the topic is unknown or is itself a DLQ, since a poison
   *     message that fails again has nowhere further to go and must stay where it is
   */
  public static String dlqOf(String topic) {
    if (!ALL.contains(topic)) {
      throw new IllegalArgumentException("not a SeatRush business topic: " + topic);
    }
    return topic + DLQ_SUFFIX;
  }

  /**
   * The topic a message is published on.
   *
   * <p>A switch over the sealed hierarchy, so a new group of messages cannot be introduced without
   * deciding where it is published.
   */
  public static String of(Message message) {
    return switch (message) {
      case SeatEvent ignored -> INVENTORY_SEAT_EVENTS;
      case InventoryCommand ignored -> INVENTORY_COMMANDS;
      case InventoryReply ignored -> INVENTORY_REPLIES;
      case PaymentCommand ignored -> PAYMENT_COMMANDS;
      case PaymentReply ignored -> PAYMENT_REPLIES;
      case OrderEvent ignored -> ORDER_EVENTS;
    };
  }
}
