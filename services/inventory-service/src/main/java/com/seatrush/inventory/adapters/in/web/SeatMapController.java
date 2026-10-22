package com.seatrush.inventory.adapters.in.web;

import com.seatrush.inventory.application.SeatMapService;
import com.seatrush.inventory.application.SeatMapSnapshot;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The seat map snapshot of README section 12.2, which is what a client renders before it starts
 * following the delta stream.
 *
 * <p>The answer is {@code {version, seats:[{id, sectionId, x, y, status}]}} and carries no hold id:
 * who holds a seat is between that user and their hold, and the map only says the seat is taken.
 */
@RestController
@RequestMapping("/api/events/{eventId}/seatmap")
public class SeatMapController {

  private final SeatMapService seatMap;

  public SeatMapController(SeatMapService seatMap) {
    this.seatMap = seatMap;
  }

  @GetMapping
  public SeatMapResponse snapshot(@PathVariable UUID eventId) {
    return SeatMapResponse.of(seatMap.snapshot(eventId));
  }

  /** The snapshot as it goes on the wire. */
  public record SeatMapResponse(long version, List<SeatResponse> seats) {

    static SeatMapResponse of(SeatMapSnapshot snapshot) {
      return new SeatMapResponse(
          snapshot.version(), snapshot.seats().stream().map(SeatResponse::of).toList());
    }

    /** One seat: where it is and what it is. */
    public record SeatResponse(String id, String sectionId, int x, int y, String status) {

      static SeatResponse of(SeatMapSnapshot.SeatMapSeat seat) {
        return new SeatResponse(
            seat.id(), seat.sectionId(), seat.x(), seat.y(), seat.status().name());
      }
    }
  }
}
