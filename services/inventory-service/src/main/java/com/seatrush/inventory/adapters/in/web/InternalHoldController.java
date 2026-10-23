package com.seatrush.inventory.adapters.in.web;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.FreezeHoldCommand;
import com.seatrush.inventory.application.FrozenHold;
import com.seatrush.inventory.application.HoldFreezeService;
import com.seatrush.inventory.domain.SeatPrice;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The internal freeze endpoint of README section 12.7.
 *
 * <p>Under {@code /internal} and not {@code /api}, which is what keeps it off the gateway: this is
 * order-service calling inventory inside the cluster, and no client may freeze a hold for an order
 * of its own choosing. The split is a path and not a filter so that the gateway's route table is
 * what enforces it, rather than a rule somebody has to remember to write; on Kubernetes a
 * NetworkPolicy closes the prefix to everything outside the cluster (README section 13).
 *
 * <p>It is the only synchronous call of the checkout SAGA. Everything after it is a message,
 * because everything after it may be retried in the background; the freeze is the one step that has
 * to fail fast, since an order whose hold has expired must never reach {@code PENDING} (README
 * section 8.3).
 */
@RestController
@RequestMapping("/internal")
public class InternalHoldController {

  private final HoldFreezeService freezes;

  public InternalHoldController(HoldFreezeService freezes) {
    this.freezes = freezes;
  }

  /**
   * Freezes a hold for an order and answers with the seats and their prices.
   *
   * <p>Idempotent for the same order: a retry of a call whose answer was lost gets the same seats
   * and the same total rather than a conflict, which is what lets order-service retry it safely.
   */
  @PostMapping("/holds/{holdId}/freeze")
  public FrozenHoldResponse freeze(
      @PathVariable UUID holdId, @Valid @RequestBody FreezeRequestBody body) {
    return FrozenHoldResponse.of(
        freezes.freeze(new FreezeHoldCommand(holdId, body.orderId(), body.userId())));
  }

  /**
   * The body of a freeze request.
   *
   * <p>The user travels in the body and not in {@code X-User-Id}, because the caller is not the
   * user: it is order-service telling inventory whose hold it believes this is, and the script
   * checks the claim.
   */
  public record FreezeRequestBody(@NotNull UUID orderId, @NotNull UUID userId) {}

  /**
   * A frozen hold as the internal API returns it.
   *
   * <p>Everything the order needs to be written in one transaction: the seats it now owns, the
   * price of each and the total. The prices are the ones inventory holds, which is the only place
   * they come from.
   */
  public record FrozenHoldResponse(
      UUID holdId,
      UUID eventId,
      UUID userId,
      UUID orderId,
      String status,
      List<SeatPriceResponse> seats,
      Money total) {

    static FrozenHoldResponse of(FrozenHold frozen) {
      return new FrozenHoldResponse(
          frozen.hold().id(),
          frozen.hold().eventId(),
          frozen.hold().userId(),
          frozen.orderId(),
          frozen.hold().status().name(),
          frozen.seats().stream().map(SeatPriceResponse::of).toList(),
          frozen.total());
    }
  }

  /** One seat with its price as {@code {"amountCents": 4500, "currency": "EUR"}}. */
  public record SeatPriceResponse(String seatId, String sectionId, Money price) {

    static SeatPriceResponse of(SeatPrice seat) {
      return new SeatPriceResponse(seat.seatId(), seat.sectionId(), seat.price());
    }
  }
}
