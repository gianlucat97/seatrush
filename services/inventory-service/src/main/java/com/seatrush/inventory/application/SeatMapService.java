package com.seatrush.inventory.application;

import com.seatrush.inventory.application.SeatMapSnapshot.SeatMapSeat;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.EventRepository;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.inventory.domain.SeatRepository;
import com.seatrush.inventory.domain.SeatSaleRepository;
import com.seatrush.inventory.domain.SeatState;
import com.seatrush.inventory.domain.SeatStateStore;
import com.seatrush.inventory.domain.SeatStatus;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The seat map: loading it into the fast path when a sale opens, and reading it back for a client
 * (README sections 6.3 and 12.2).
 *
 * <p>Postgres owns the venue and the sales; Redis owns the sale in progress. Everything here is one
 * of the two directions between them, which is why the warm-up and the snapshot live together: they
 * are the same join read from opposite ends.
 */
@Service
public class SeatMapService {

  private static final Logger log = LoggerFactory.getLogger(SeatMapService.class);

  private final EventRepository events;
  private final SeatRepository seats;
  private final SeatSaleRepository sales;
  private final SeatStateStore seatState;

  public SeatMapService(
      EventRepository events,
      SeatRepository seats,
      SeatSaleRepository sales,
      SeatStateStore seatState) {
    this.events = events;
    this.seats = seats;
    this.sales = sales;
    this.seatState = seatState;
  }

  /**
   * Loads the seat map of an event into the fast path, so holds can be served from Redis.
   *
   * <p>Called when a sale opens, and the same call rebuilds the state after a Redis data loss
   * (README section 16). It is deliberately a full rebuild rather than a merge: a partial state is
   * the one thing the hold path cannot work with, and a seat that Redis has never heard of would be
   * refused as unavailable with no way to tell why.
   *
   * @return how many seats were loaded
   * @throws EventNotFoundException if there is no such event
   */
  @Transactional(readOnly = true)
  public int warmUp(UUID eventId) {
    Event event = events.findById(eventId).orElseThrow(() -> new EventNotFoundException(eventId));
    List<Seat> venue = seats.findByEvent(eventId);
    if (venue.isEmpty()) {
      throw new IllegalStateException("event " + eventId + " has no seats to warm up");
    }
    Set<String> sold = sales.findSoldSeatIds(eventId);

    seatState.warmUp(eventId, venue.stream().map(Seat::id).toList(), sold);

    log.info(
        "warmed up event {} with {} seats, {} of them already sold",
        event.id(),
        venue.size(),
        sold.size());
    return venue.size();
  }

  /**
   * The seat map of an event at its current version.
   *
   * <p>When the event has no state in the fast path, which is every event before its sale opens and
   * every event whose sale is over, the snapshot is built from Postgres alone: the sold seats are
   * the rows of {@code seat_sale} and everything else is available, at version zero. A client gets
   * a correct map of an event that is not on sale rather than an error it has no use for.
   *
   * @throws EventNotFoundException if there is no such event
   */
  @Transactional(readOnly = true)
  public SeatMapSnapshot snapshot(UUID eventId) {
    requireEvent(eventId);
    List<Seat> venue = seats.findByEvent(eventId);
    Optional<SeatState> state = seatState.read(eventId);

    long version = state.map(SeatState::version).orElse(0L);
    Set<String> sold = state.isPresent() ? Set.of() : sales.findSoldSeatIds(eventId);

    List<SeatMapSeat> map =
        venue.stream()
            .map(
                seat ->
                    new SeatMapSeat(
                        seat.id(),
                        seat.sectionId(),
                        seat.x(),
                        seat.y(),
                        state
                            .map(fastPath -> fastPath.statusOf(seat.id()))
                            .orElseGet(
                                () ->
                                    sold.contains(seat.id())
                                        ? SeatStatus.SOLD
                                        : SeatStatus.AVAILABLE)))
            .toList();
    return new SeatMapSnapshot(eventId, version, map);
  }

  private void requireEvent(UUID eventId) {
    if (events.findById(eventId).isEmpty()) {
      throw new EventNotFoundException(eventId);
    }
  }
}
