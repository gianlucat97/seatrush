package com.seatrush.messaging.outbox;

import java.time.Clock;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The outbox table without a database, for the unit tests of the relay.
 *
 * <p>It keeps the two properties the relay is written against and nothing else: rows come back in
 * the order they were appended, and a published row never comes back. The locking of README section
 * 9.5 is deliberately absent, because it cannot be imitated: {@code SKIP LOCKED} and the ordering
 * guard are asserted against a real Postgres in {@code OutboxRelayIT}.
 */
final class InMemoryOutboxStore implements OutboxStore {

  private final Clock clock;
  private final List<OutboxEntry> entries = new ArrayList<>();
  private final Set<Long> published = new HashSet<>();
  private long nextId = 1;

  InMemoryOutboxStore(Clock clock) {
    this.clock = clock;
  }

  @Override
  public void append(String aggregateKey, String topic, String message) {
    entries.add(new OutboxEntry(nextId++, aggregateKey, topic, message, clock.instant()));
  }

  @Override
  public List<OutboxEntry> claim(int batchSize) {
    return entries.stream()
        .filter(entry -> !published.contains(entry.id()))
        .limit(batchSize)
        .toList();
  }

  @Override
  public void markPublished(List<Long> ids) {
    published.addAll(ids);
  }

  /** Every row ever appended, published or not. */
  List<OutboxEntry> all() {
    return List.copyOf(entries);
  }

  /** The rows still waiting for the relay. */
  List<OutboxEntry> unpublished() {
    return entries.stream().filter(entry -> !published.contains(entry.id())).toList();
  }
}
