package com.seatrush.inventory.application;

import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.inventory.domain.SeatRepository;
import com.seatrush.inventory.domain.SeatSaleRepository;
import com.seatrush.inventory.domain.Section;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The two repository ports in memory, for the use case tests.
 *
 * <p>A fake rather than a mock: the use cases are interesting because of what they do across two
 * stores (insert an event and its seats, move a status only if it is still the expected one), and a
 * mock would make those tests assertions about calls rather than about outcomes. The behaviour that
 * needs Postgres to be believed is covered by the integration tests.
 */
final class InMemoryCatalog implements EventRepository, SeatRepository, SeatSaleRepository {

  private final Map<UUID, Event> events = new LinkedHashMap<>();
  private final Map<UUID, List<Section>> sections = new HashMap<>();
  private final Map<UUID, List<Seat>> seats = new HashMap<>();
  private final Map<UUID, Set<String>> sold = new HashMap<>();

  /** Set to true to make every status update lose its race, as a concurrent one would. */
  private boolean statusUpdatesLose;

  @Override
  public void insert(Event event, List<Section> newSections) {
    if (events.putIfAbsent(event.id(), event) != null) {
      throw new IllegalStateException("event " + event.id() + " already exists");
    }
    sections.put(event.id(), List.copyOf(newSections));
  }

  @Override
  public Optional<Event> findById(UUID eventId) {
    return Optional.ofNullable(events.get(eventId));
  }

  @Override
  public List<Event> findAll() {
    List<Event> all = new ArrayList<>(events.values());
    all.sort(Comparator.comparing(Event::onSaleAt).thenComparing(Event::createdAt));
    return all;
  }

  @Override
  public List<Section> findSections(UUID eventId) {
    return sections.getOrDefault(eventId, List.of());
  }

  @Override
  public boolean updateStatus(UUID eventId, EventStatus from, EventStatus to) {
    Event stored = events.get(eventId);
    if (statusUpdatesLose || stored == null || stored.status() != from) {
      return false;
    }
    events.put(
        eventId, new Event(eventId, stored.name(), stored.onSaleAt(), to, stored.createdAt()));
    return true;
  }

  @Override
  public void insertAll(List<Seat> newSeats) {
    for (Seat seat : newSeats) {
      seats.computeIfAbsent(seat.eventId(), id -> new ArrayList<>()).add(seat);
    }
  }

  @Override
  public List<Seat> findByEvent(UUID eventId) {
    return List.copyOf(seats.getOrDefault(eventId, List.of()));
  }

  @Override
  public int countByEvent(UUID eventId) {
    return seats.getOrDefault(eventId, List.of()).size();
  }

  @Override
  public Set<String> findSoldSeatIds(UUID eventId) {
    return Set.copyOf(sold.getOrDefault(eventId, Set.of()));
  }

  @Override
  public int countSoldSeats(UUID eventId) {
    return sold.getOrDefault(eventId, Set.of()).size();
  }

  /** Records a sale, the way the commit procedure will once it exists. */
  void sell(UUID eventId, String... seatIds) {
    sold.computeIfAbsent(eventId, id -> new HashSet<>()).addAll(List.of(seatIds));
  }

  void loseEveryStatusUpdate() {
    statusUpdatesLose = true;
  }
}
