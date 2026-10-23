package com.seatrush.messaging.consumer;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The processed_message table without a database.
 *
 * <p>It keeps the transaction check of the real one, because that is the contract the consumer is
 * written against. What it cannot imitate is the rollback: a handler that throws leaves the id
 * marked here, which is why {@code IdempotentConsumerIT} is the test that proves a failed handler
 * gets its redelivery.
 */
final class InMemoryProcessedMessages implements ProcessedMessages {

  private final Set<UUID> claimed = new LinkedHashSet<>();

  @Override
  public boolean markProcessed(UUID messageId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("a message must be claimed inside a transaction");
    }
    return claimed.add(messageId);
  }

  Set<UUID> claimed() {
    return Set.copyOf(claimed);
  }
}
