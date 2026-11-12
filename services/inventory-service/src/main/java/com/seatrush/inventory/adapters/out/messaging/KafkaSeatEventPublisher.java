package com.seatrush.inventory.adapters.out.messaging;

import com.seatrush.inventory.domain.SeatEventPublisher;
import com.seatrush.inventory.domain.SeatEventRecord;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.EventSoldOut;
import com.seatrush.messaging.messages.SeatEvent;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.messaging.messages.SeatsReleased;
import com.seatrush.messaging.messages.SeatsSold;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

/**
 * Publishes seat changes to {@code inventory.seat-events} (README section 10.1).
 *
 * <p>Keyed by {@code eventId}, so every change to one seat map lands in one partition and arrives
 * in the order the versions were handed out. That ordering is the whole reason the key is the event
 * and not the seat.
 *
 * <p>The batch is sent in order and then waited on as a whole: the producer is idempotent and keeps
 * at most five requests in flight, so the broker stores them in the order they were sent, and
 * waiting once instead of per record keeps a batch of 500 from costing 500 round trips.
 */
@Component
public class KafkaSeatEventPublisher implements SeatEventPublisher {

  private static final Logger log = LoggerFactory.getLogger(KafkaSeatEventPublisher.class);

  private final KafkaTemplate<String, String> kafka;
  private final MessageFactory messages;
  private final MessageCodec codec;

  public KafkaSeatEventPublisher(
      KafkaTemplate<String, String> kafka, MessageFactory messages, MessageCodec codec) {
    this.kafka = kafka;
    this.messages = messages;
    this.codec = codec;
  }

  @Override
  public List<String> publishAll(List<SeatEventRecord> records) {
    Objects.requireNonNull(records, "records must not be null");
    if (records.isEmpty()) {
      return List.of();
    }

    List<CompletableFuture<SendResult<String, String>>> sent = new ArrayList<>(records.size());
    for (SeatEventRecord record : records) {
      MessageEnvelope<SeatEvent> envelope = messages.wrapAt(payloadOf(record), record.occurredAt());
      sent.add(
          kafka.send(
              Topics.INVENTORY_SEAT_EVENTS,
              envelope.partitionKey().toString(),
              codec.encodeToString(envelope)));
    }

    // Stops at the first failure instead of reporting what happened to land after it: the caller
    // acknowledges a prefix, and anything beyond the gap is read again rather than skipped.
    List<String> acknowledged = new ArrayList<>(records.size());
    for (int i = 0; i < sent.size(); i++) {
      try {
        sent.get(i).join();
        acknowledged.add(records.get(i).recordId());
      } catch (RuntimeException notSent) {
        log.warn(
            "a seat event of event {} did not reach Kafka, {} of {} were published",
            records.get(i).eventId(),
            acknowledged.size(),
            records.size(),
            notSent);
        break;
      }
    }
    return List.copyOf(acknowledged);
  }

  /**
   * The message one stream entry becomes.
   *
   * <p>The names in the stream are the names of the message types of README section 10.3, because
   * the Lua scripts write the type they mean rather than a code this class would have to translate.
   */
  private static SeatEvent payloadOf(SeatEventRecord record) {
    return switch (record.type()) {
      case "SeatsHeld" ->
          new SeatsHeld(record.eventId(), record.seatIds(), record.version(), record.holdId());
      case "SeatsReleased" ->
          new SeatsReleased(record.eventId(), record.seatIds(), record.version(), record.holdId());
      case "SeatsSold" ->
          new SeatsSold(record.eventId(), record.seatIds(), record.version(), record.holdId());
      case "EventSoldOut" -> new EventSoldOut(record.eventId());
      default ->
          throw new IllegalStateException(
              "the stream of event %s carries an unknown seat event: %s"
                  .formatted(record.eventId(), record.type()));
    };
  }
}
