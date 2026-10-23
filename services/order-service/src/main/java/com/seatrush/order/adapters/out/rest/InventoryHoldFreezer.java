package com.seatrush.order.adapters.out.rest;

import com.seatrush.common.error.ErrorCode;
import com.seatrush.common.money.Money;
import com.seatrush.order.domain.FrozenHold;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldExpiredException;
import com.seatrush.order.domain.HoldFreezer;
import com.seatrush.order.domain.HoldNotFoundException;
import com.seatrush.order.domain.HoldNotOwnedException;
import com.seatrush.order.domain.InventoryUnavailableException;
import com.seatrush.order.domain.OrderItem;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClient.RequestHeadersSpec.ConvertibleClientHttpResponse;
import org.springframework.web.client.RestClientException;

/**
 * The {@link HoldFreezer} over the internal API of inventory (README section 12.7).
 *
 * <p>Inventory answers every refusal as a Problem Details document carrying the stable {@code code}
 * of README section 12.6, and this class branches on that code rather than on the HTTP status. The
 * two agree today, but the code is the published contract and the status is a transport detail: a
 * refusal that one day moves from 409 to 422 must not silently become a different refusal here.
 *
 * <p>Anything this class cannot make sense of becomes {@link InventoryUnavailableException}, which
 * the API reports as retryable. That is the safe direction: the freeze is idempotent for the same
 * order, so a client that retries either gets the seats or a refusal it can act on, while guessing
 * at an unknown answer could create an order for a hold inventory never froze.
 */
@Component
public class InventoryHoldFreezer implements HoldFreezer {

  private static final Logger log = LoggerFactory.getLogger(InventoryHoldFreezer.class);

  private final RestClient inventory;

  /**
   * @param inventoryRestClient named after its bean, so that a second {@link RestClient} added to
   *     this service later resolves by name instead of becoming an ambiguous injection
   */
  public InventoryHoldFreezer(RestClient inventoryRestClient) {
    this.inventory = Objects.requireNonNull(inventoryRestClient, "inventory must not be null");
  }

  @Override
  public FrozenHold freeze(UUID holdId, UUID orderId, UUID userId) {
    Objects.requireNonNull(holdId, "holdId must not be null");
    Objects.requireNonNull(orderId, "orderId must not be null");
    Objects.requireNonNull(userId, "userId must not be null");

    FreezeResponse answer;
    try {
      answer =
          inventory
              .post()
              .uri("/internal/holds/{holdId}/freeze", holdId)
              .body(new FreezeRequest(orderId, userId))
              .exchange(
                  (request, response) -> {
                    if (response.getStatusCode().is2xxSuccessful()) {
                      return response.bodyTo(FreezeResponse.class);
                    }
                    throw refusalOf(holdId, response.getStatusCode(), problemOf(response));
                  });
    } catch (RestClientException unreachable) {
      // A connection refused, a timeout, or a body that is not what this build can read. Nothing
      // was created here either way, so the caller is told to try again.
      throw new InventoryUnavailableException(
          "inventory did not answer the freeze of hold " + holdId, unreachable);
    }
    return toFrozenHold(holdId, answer);
  }

  /**
   * Reads the Problem Details of a refusal, tolerating one that is not there.
   *
   * <p>A gateway or a proxy between the two services can answer with something that is not a
   * SeatRush problem at all, and that must not turn into a parse failure that hides the status.
   */
  private static ProblemResponse problemOf(ConvertibleClientHttpResponse response) {
    try {
      ProblemResponse problem = response.bodyTo(ProblemResponse.class);
      return problem == null ? ProblemResponse.empty() : problem;
    } catch (RestClientException notAProblemDocument) {
      log.warn(
          "inventory refused a freeze with a body this service cannot read", notAProblemDocument);
      return ProblemResponse.empty();
    }
  }

  /**
   * The exception one refusal becomes.
   *
   * <p>A switch over the error codes this endpoint can answer with. An unknown code, or a refusal
   * without one, is reported as inventory being unavailable rather than guessed at.
   */
  private static RuntimeException refusalOf(
      UUID holdId, HttpStatusCode status, ProblemResponse problem) {
    ErrorCode code = problem.errorCode();
    if (code == null) {
      return new InventoryUnavailableException(
          "inventory refused the freeze of hold %s with status %s and no error code"
              .formatted(holdId, status),
          null);
    }
    return switch (code) {
      case HOLD_EXPIRED -> new HoldExpiredException(holdId, problem.detailOrCode());
      case HOLD_ALREADY_USED -> new HoldAlreadyUsedException(holdId, problem.detailOrCode());
      case NOT_FOUND -> new HoldNotFoundException(holdId);
      case FORBIDDEN -> new HoldNotOwnedException(holdId);
      default ->
          new InventoryUnavailableException(
              "inventory refused the freeze of hold %s with %s".formatted(holdId, code), null);
    };
  }

  private static FrozenHold toFrozenHold(UUID holdId, FreezeResponse answer) {
    if (answer == null) {
      throw new InventoryUnavailableException(
          "inventory answered the freeze of hold " + holdId + " with no body", null);
    }
    List<OrderItem> seats =
        answer.seats().stream().map(seat -> new OrderItem(seat.seatId(), seat.price())).toList();
    return new FrozenHold(
        answer.holdId(), answer.eventId(), answer.userId(), seats, answer.total());
  }

  /** The body of the freeze call: which order is taking the hold, and whose it should be. */
  record FreezeRequest(UUID orderId, UUID userId) {}

  /** What inventory answers with when the freeze succeeds. */
  record FreezeResponse(
      UUID holdId,
      UUID eventId,
      UUID userId,
      UUID orderId,
      String status,
      List<SeatResponse> seats,
      Money total) {}

  /** One priced seat of the answer. */
  record SeatResponse(String seatId, String sectionId, Money price) {}
}
