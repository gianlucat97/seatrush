package com.seatrush.realtime.application;

import com.seatrush.realtime.domain.SeatMapAudience;
import com.seatrush.realtime.domain.SeatMapDelta;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * An audience that keeps what it was sent, standing in for the connections of a pod.
 *
 * <p>It also lets a test say how many clients are watching a sale, which is the other half of the
 * port: whether a batch is kept or forgotten depends on whether anybody is there to receive it.
 */
class RecordingAudience implements SeatMapAudience {

  private final List<SeatMapDelta> sent = new CopyOnWriteArrayList<>();
  private final Map<UUID, Integer> watchers = new ConcurrentHashMap<>();

  @Override
  public void send(SeatMapDelta delta) {
    sent.add(delta);
  }

  @Override
  public int watching(UUID eventId) {
    return watchers.getOrDefault(eventId, 0);
  }

  void watch(UUID eventId, int clients) {
    watchers.put(eventId, clients);
  }

  List<SeatMapDelta> sent() {
    return List.copyOf(sent);
  }

  SeatMapDelta onlyDelta() {
    List<SeatMapDelta> deltas = sent();
    if (deltas.size() != 1) {
      throw new AssertionError("expected one delta, got " + deltas.size());
    }
    return deltas.getFirst();
  }
}
