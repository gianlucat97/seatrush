package com.seatrush.realtime.adapters.in.messaging;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.consumer.MessageRouter;
import com.seatrush.messaging.messages.EventSoldOut;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.messaging.messages.SeatsReleased;
import com.seatrush.messaging.messages.SeatsSold;
import com.seatrush.realtime.application.SeatMapDeltas;
import com.seatrush.realtime.domain.SeatMapChange;
import com.seatrush.realtime.domain.SeatStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Every change to every seat map, as this pod hears it (README sections 6.6 and 10.1).
 *
 * <p>Three of the four messages are the same message with a different word in it, and this is where
 * that becomes true: a hold, a release and a sale all turn into some seats and a status, and
 * nothing past this class knows which of them it was. The hold id they carry is dropped here,
 * because who holds a seat is between that user and their hold, and a seat map only says that the
 * seat is taken (README section 12.2).
 *
 * <p>The time of a change is taken from the envelope and never from a clock here, which is what
 * makes the propagation histogram of README section 14.1 measure the age of the change rather than
 * the age of the record.
 *
 * <p>{@code EventSoldOut} is the fourth message and is not a seat change at all. It carries no
 * seats and no version, and what it means for this stream is that the map it names will never move
 * again: the seats that took the last of the sale are pushed at once instead of waiting out their
 * window. Handled rather than ignored, because a message nobody handles is a message in the dead
 * letter topic (see {@link MessageRouter}), and an operator reading a DLQ should find a problem
 * there and not a design decision.
 *
 * <p>The group is this pod's own, which is the broadcast pattern of README section 6.6: every pod
 * gets every seat event, because every pod holds its own clients and keeps nothing shared. That is
 * the opposite of every other consumer in the system, where one group per service means each piece
 * of work is done by one pod.
 *
 * <p>Nothing guards against a redelivery here, and nothing has to: a seat event carries the version
 * it was made at, so the batch it lands in recognises a duplicate by arithmetic (README section
 * 9.5). There is no {@code processed_message} table to write it in, because this service owns no
 * database at all.
 */
@Component
public class SeatEventListener {

  private static final Logger log = LoggerFactory.getLogger(SeatEventListener.class);

  private final MessageRouter router;

  public SeatEventListener(SeatMapDeltas deltas, MessageCodec codec) {
    this.router =
        MessageRouter.using(codec)
            .handle(
                SeatsHeld.class,
                envelope -> {
                  SeatsHeld held = envelope.payload();
                  deltas.record(
                      new SeatMapChange(
                          held.eventId(),
                          held.version(),
                          envelope.occurredAt(),
                          held.seatIds(),
                          SeatStatus.HELD));
                })
            .handle(
                SeatsReleased.class,
                envelope -> {
                  SeatsReleased released = envelope.payload();
                  deltas.record(
                      new SeatMapChange(
                          released.eventId(),
                          released.version(),
                          envelope.occurredAt(),
                          released.seatIds(),
                          SeatStatus.AVAILABLE));
                })
            .handle(
                SeatsSold.class,
                envelope -> {
                  SeatsSold sold = envelope.payload();
                  deltas.record(
                      new SeatMapChange(
                          sold.eventId(),
                          sold.version(),
                          envelope.occurredAt(),
                          sold.seatIds(),
                          SeatStatus.SOLD));
                })
            .handle(EventSoldOut.class, envelope -> deltas.settle(envelope.payload().eventId()))
            .build();
  }

  @KafkaListener(topics = Topics.INVENTORY_SEAT_EVENTS, groupId = "#{@broadcastConsumerGroup.id()}")
  public void onSeatEvent(ConsumerRecord<String, String> record) {
    log.debug("the seat map of {} changed, at offset {}", record.key(), record.offset());
    router.route(record.value());
  }
}
