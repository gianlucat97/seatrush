package com.seatrush.messaging.outbox;

import java.util.ArrayList;
import java.util.List;

/**
 * A {@link MessagePublisher} that records what it was given and can refuse part of a batch.
 *
 * <p>Refusing from a given row onwards is the behaviour the relay has to survive: the broker took a
 * prefix, and the rows after it must stay unpublished rather than be marked.
 */
final class RecordingPublisher implements MessagePublisher {

  private final List<OutboxEntry> seen = new ArrayList<>();
  private int acceptAtMost = Integer.MAX_VALUE;
  private RuntimeException failure;

  @Override
  public List<Long> publishAll(List<OutboxEntry> entries) {
    if (failure != null) {
      throw failure;
    }
    seen.addAll(entries);
    return entries.stream().limit(acceptAtMost).map(OutboxEntry::id).toList();
  }

  /** Makes the broker take only the first {@code count} rows of every batch. */
  void acceptAtMost(int count) {
    this.acceptAtMost = count;
  }

  /** Makes the broker unreachable. */
  void failWith(RuntimeException failure) {
    this.failure = failure;
  }

  List<OutboxEntry> seen() {
    return List.copyOf(seen);
  }
}
