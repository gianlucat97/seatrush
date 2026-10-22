package com.seatrush.inventory.domain;

import java.util.Optional;
import java.util.UUID;

/**
 * Which event a hold belongs to.
 *
 * <p>It exists because the API of README section 12.4 addresses a hold by its id alone, while every
 * key of a hold is scoped to its event: {@code GET /api/holds/{holdId}} has to find the event
 * before it can find the hold.
 *
 * <p>It is deliberately a separate port from {@link SeatHoldStore}, because it is the one piece of
 * hold state that cannot live under the hash tag of an event, and therefore the one piece that is
 * written outside the atomic script. A hold whose entry is missing still expires, still gets
 * committed and still shows up in the seat map; what its user loses is the ability to read or
 * release it early, which is why the entry is written immediately after the hold and kept for as
 * long as the hold itself.
 */
public interface HoldDirectory {

  /** Records which event a hold belongs to. */
  void remember(UUID holdId, UUID eventId);

  /** The event of a hold, or empty when nothing was recorded or the entry is gone. */
  Optional<UUID> eventOf(UUID holdId);
}
