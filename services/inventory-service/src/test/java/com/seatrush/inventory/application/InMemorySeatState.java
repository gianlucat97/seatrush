package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.SeatState;
import com.seatrush.inventory.domain.SeatStateStore;
import com.seatrush.inventory.domain.SeatStatus;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The fast path in a {@link Map}, for the use case tests.
 *
 * <p>It records every warm-up it was asked for, because the interesting question at this level is
 * not how Redis stores a seat but whether the use case asked for the right seats and the right
 * sales. What Redis actually does with them is covered by the integration tests.
 */
final class InMemorySeatState implements SeatStateStore {

  private final Map<UUID, SeatState> states = new HashMap<>();
  private final List<UUID> warmUps = new java.util.ArrayList<>();

  @Override
  public void warmUp(UUID eventId, Collection<String> seatIds, Set<String> soldSeatIds) {
    Map<String, SeatStatus> statuses = new LinkedHashMap<>();
    for (String seatId : seatIds) {
      statuses.put(seatId, soldSeatIds.contains(seatId) ? SeatStatus.SOLD : SeatStatus.AVAILABLE);
    }
    int available = (int) seatIds.stream().filter(seatId -> !soldSeatIds.contains(seatId)).count();
    states.put(eventId, new SeatState(eventId, 0L, available, statuses));
    warmUps.add(eventId);
  }

  @Override
  public Optional<SeatState> read(UUID eventId) {
    return Optional.ofNullable(states.get(eventId));
  }

  @Override
  public boolean isWarm(UUID eventId) {
    return states.containsKey(eventId);
  }

  @Override
  public void clear(UUID eventId) {
    states.remove(eventId);
  }

  /** Replaces the state of an event, so a test can pretend a hold or a sale happened. */
  void stateIs(UUID eventId, long version, int available, Map<String, SeatStatus> statuses) {
    states.put(eventId, new SeatState(eventId, version, available, statuses));
  }

  /** Every event this store was asked to warm up, in order. */
  List<UUID> warmUps() {
    return List.copyOf(warmUps);
  }
}
