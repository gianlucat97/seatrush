package com.seatrush.inventory.application;

import com.seatrush.messaging.consumer.ProcessedMessages;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The processed_message table in memory, with the two behaviours this service depends on.
 *
 * <p>The transaction check, because the claim and the work it guards have to commit together. And
 * the rollback, which this double does imitate: a commit whose sale the guard refused answers with
 * SeatsCommitFailed from a second attempt in a new transaction, and that attempt only works because
 * the first one gave the claim back.
 */
final class InMemoryProcessedMessages implements ProcessedMessages {

  private final Set<UUID> claimed = new LinkedHashSet<>();

  @Override
  public boolean markProcessed(UUID messageId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("a message must be claimed inside a transaction");
    }
    if (!claimed.add(messageId)) {
      return false;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            if (status != TransactionSynchronization.STATUS_COMMITTED) {
              claimed.remove(messageId);
            }
          }
        });
    return true;
  }

  int count() {
    return claimed.size();
  }
}
