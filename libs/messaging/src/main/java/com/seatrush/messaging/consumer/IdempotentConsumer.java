package com.seatrush.messaging.consumer;

import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.messages.Message;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Objects;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Runs a handler at most once per message (README section 9.4).
 *
 * <p>The claim on the message, the state change and the next command in the outbox are one
 * transaction, which is what README section 9.7 asks of every SAGA step. That single transaction is
 * the reason a consumer needs nothing else to be idempotent: either all three happened, and the
 * redelivery is recognised and dropped, or none of them did, and the redelivery is the retry.
 *
 * <p>A handler that throws rolls the claim back with its own work, so the record is retried by
 * Kafka and, after the configured attempts, parked in the dead letter topic of README section 10.1.
 */
public class IdempotentConsumer {

  private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

  private final TransactionTemplate transactions;
  private final ProcessedMessages processed;
  private final MeterRegistry meters;

  public IdempotentConsumer(
      TransactionTemplate transactions, ProcessedMessages processed, MeterRegistry meters) {
    this.transactions = Objects.requireNonNull(transactions, "transactions must not be null");
    this.processed = Objects.requireNonNull(processed, "processed must not be null");
    this.meters = Objects.requireNonNull(meters, "meters must not be null");
  }

  /**
   * Hands the envelope to the handler unless it has already been handled.
   *
   * @return {@code true} if the handler ran, {@code false} if this was a redelivery
   */
  public <T extends Message> boolean handleOnce(
      MessageEnvelope<T> envelope, Consumer<MessageEnvelope<T>> handler) {
    Objects.requireNonNull(envelope, "envelope must not be null");
    Objects.requireNonNull(handler, "handler must not be null");

    Boolean handled =
        transactions.execute(
            status -> {
              if (!processed.markProcessed(envelope.messageId())) {
                return false;
              }
              handler.accept(envelope);
              return true;
            });

    if (Boolean.FALSE.equals(handled)) {
      meters.counter("seatrush.messages.duplicates", "type", envelope.type()).increment();
      log.debug(
          "{} {} was already processed and is dropped", envelope.type(), envelope.messageId());
      return false;
    }
    return true;
  }
}
