package com.seatrush.messaging.outbox;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The {@link MessagePublisher} on Kafka.
 *
 * <p>The batch is sent in one go and then waited on as a whole. The producer is idempotent and
 * keeps a bounded number of requests in flight, so the broker stores the records of one partition
 * in the order they were sent, and waiting once instead of per record keeps a batch of 500 from
 * costing 500 round trips.
 */
public class KafkaMessagePublisher implements MessagePublisher {

  private static final Logger log = LoggerFactory.getLogger(KafkaMessagePublisher.class);

  private final KafkaTemplate<String, String> kafka;

  public KafkaMessagePublisher(KafkaTemplate<String, String> kafka) {
    this.kafka = Objects.requireNonNull(kafka, "kafka must not be null");
  }

  @Override
  public List<Long> publishAll(List<OutboxEntry> entries) {
    Objects.requireNonNull(entries, "entries must not be null");
    if (entries.isEmpty()) {
      return List.of();
    }

    List<CompletableFuture<SendResult<String, String>>> sent = new ArrayList<>(entries.size());
    for (OutboxEntry entry : entries) {
      sent.add(kafka.send(entry.topic(), entry.aggregateKey(), entry.message()));
    }

    List<Long> published = new ArrayList<>(entries.size());
    for (int i = 0; i < sent.size(); i++) {
      try {
        sent.get(i).join();
        published.add(entries.get(i).id());
      } catch (RuntimeException notSent) {
        // Stops at the first failure instead of reporting what happened to land after it. The row
        // stays unpublished and is claimed again, which keeps the aggregate in order at the cost
        // of republishing what did make it: consumers are idempotent, gaps are not recoverable.
        log.warn(
            "outbox row {} for {} did not reach {}, {} of {} were published",
            entries.get(i).id(),
            entries.get(i).aggregateKey(),
            entries.get(i).topic(),
            published.size(),
            entries.size(),
            notSent);
        break;
      }
    }
    return List.copyOf(published);
  }
}
