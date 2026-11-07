package com.seatrush.inventory.adapters.in.web;

import com.seatrush.inventory.application.SeatMapService;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal rebuild endpoint of README section 12.7.
 *
 * <p>The recovery README section 16 names for a Redis restart with data loss. Everything in the
 * fast path is hot state and none of it is persisted on purpose, so a restart takes the seat map,
 * the holds and the available counter of every live sale with it. Half of what that row promises
 * then happens by itself: the holds are gone and their users re-hold, and a frozen hold whose SAGA
 * comes back fails its commit and compensates. The other half is this, and until it existed a sale
 * that lost its state stayed open in the catalog with nothing behind it, which is a sale nobody can
 * buy anything in and which looks from the outside like a sale where nothing is happening.
 *
 * <p>Under {@code /internal} like the freeze beside it, for the same reason: this is an operator or
 * a runbook acting from inside the deployment, and a client that could re-warm a sale could also
 * wipe every hold in it by doing so at the wrong moment. The gateway does not route the prefix and
 * a NetworkPolicy closes it from outside the cluster (README section 13).
 *
 * <p>It is the same call the warm-up makes when a sale opens, which is what makes it safe to
 * repeat: {@link SeatMapService#warmUp} is a full rebuild from {@code seat}, with the sold seats
 * read from {@code seat_sale}, so running it twice leaves the same state and running it on a sale
 * that never lost anything only costs the write. The holds it does not restore are the ones section
 * 16 says are lost.
 */
@RestController
@RequestMapping("/internal")
public class InternalEventController {

  private final SeatMapService seatMaps;

  public InternalEventController(SeatMapService seatMaps) {
    this.seatMaps = seatMaps;
  }

  /**
   * Loads the seat map of an event back into Redis from Postgres.
   *
   * @return how many seats were loaded, which is the number an operator checks against the venue
   */
  @PostMapping("/events/{eventId}/rebuild")
  public RebuildResponse rebuild(@PathVariable UUID eventId) {
    return new RebuildResponse(eventId, seatMaps.warmUp(eventId));
  }

  /** What a rebuild did, which is one number: the size of the venue it put back. */
  public record RebuildResponse(UUID eventId, int seats) {}
}
