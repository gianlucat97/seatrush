package com.seatrush.inventory.application;

import com.seatrush.messaging.outbox.OutboxEntry;
import com.seatrush.messaging.outbox.OutboxStore;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * The outbox table in memory, keeping the transaction check of the real one.
 *
 * <p>The check is the point: the guarantee of README section 9.5 is that the reply and the sale are
 * written together, and a test that let an append happen outside a transaction would pass while the
 * service shipped the bug the outbox exists to prevent.
 */
final class RecordingOutboxStore implements OutboxStore {

  private final Clock clock;
  private final List<OutboxEntry> appended = new ArrayList<>();

  RecordingOutboxStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void append(String aggregateKey, String topic, String message) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      throw new IllegalStateException("the outbox must be used inside a transaction");
    }
    appended.add(
        new OutboxEntry(appended.size() + 1L, aggregateKey, topic, message, clock.instant()));
  }

  @Override
  public List<OutboxEntry> claim(int batchSize) {
    throw new UnsupportedOperationException("the relay is covered by the tests of its own module");
  }

  @Override
  public void markPublished(List<Long> ids) {
    throw new UnsupportedOperationException("the relay is covered by the tests of its own module");
  }

  List<OutboxEntry> appended() {
    return List.copyOf(appended);
  }
}
