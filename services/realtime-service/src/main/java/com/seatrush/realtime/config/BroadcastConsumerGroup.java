package com.seatrush.realtime.config;

import com.seatrush.common.id.IdGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The Kafka consumer group this pod reads seat events under, which is this pod's alone (README
 * section 6.6).
 *
 * <p>Every other consumer in SeatRush shares one group per service, because a message is work and
 * work is done once. Seat events are not work: they are news, and every pod needs all of it,
 * because every pod holds different connections. A group per pod is how Kafka says that. The cost
 * is that the broker carries one group per running pod of this service, and the alternative, a
 * shared group, would mean each pod receiving a twelfth of the changes and each client seeing a
 * twelfth of the seat map.
 *
 * <p>A fresh name on every start, not the pod's name. What this service wants is the changes from
 * now on: a group that remembered where it left off would, after a restart or a rollout, replay
 * minutes of seat events to clients that have just loaded a fresh snapshot, and every one of those
 * deltas would be discarded by version on arrival. A never seen group with {@code
 * auto-offset-reset: latest} starts exactly where a new pod should. The groups a rollout leaves
 * behind hold no offsets anybody needs and the broker expires them on its own.
 *
 * <p>The id is a UUIDv7, so the groups of one deployment sort by the order their pods started,
 * which is what makes a listing of them readable during an incident.
 */
@Component
public class BroadcastConsumerGroup {

  /** The prefix every group of this service shares, so that a listing of groups is self evident. */
  public static final String PREFIX = "realtime";

  private static final Logger log = LoggerFactory.getLogger(BroadcastConsumerGroup.class);

  private final String id;

  public BroadcastConsumerGroup(IdGenerator ids) {
    this.id = PREFIX + "-" + ids.newId();
    log.info("reading seat events under {}, the consumer group of this pod alone", id);
  }

  /** The group name, read by the listener through {@code #{@broadcastConsumerGroup.id()}}. */
  public String id() {
    return id;
  }
}
