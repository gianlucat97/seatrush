package com.seatrush.order.adapters.out.rest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.seatrush.common.money.Money;
import com.seatrush.order.domain.FrozenHold;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldExpiredException;
import com.seatrush.order.domain.HoldNotFoundException;
import com.seatrush.order.domain.HoldNotOwnedException;
import com.seatrush.order.domain.InventoryUnavailableException;
import com.seatrush.order.domain.OrderItem;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.response.DefaultResponseCreator;
import org.springframework.web.client.RestClient;

/**
 * What an answer from inventory becomes on this side of the call (README sections 12.6 and 12.7).
 *
 * <p>Every refusal of the freeze endpoint has a test, because this mapping is the whole reason the
 * adapter exists: a code that fell through to the wrong branch would turn a hold that expired into
 * a conflict, or worse, into a retryable failure a client would hammer.
 */
class InventoryHoldFreezerTest {

  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final String FREEZE_URL =
      "http://inventory:8081/internal/holds/" + HOLD_ID + "/freeze";

  private MockRestServiceServer inventory;
  private InventoryHoldFreezer freezer;

  @BeforeEach
  void anInventoryToCall() {
    RestClient.Builder builder = RestClient.builder().baseUrl("http://inventory:8081");
    inventory = MockRestServiceServer.bindTo(builder).build();
    freezer = new InventoryHoldFreezer(builder.build());
  }

  @Test
  void readsBackTheSeatsAndThePricesInventoryHolds() {
    inventory
        .expect(requestTo(FREEZE_URL))
        .andExpect(method(HttpMethod.POST))
        .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
        .andRespond(withSuccess(frozenBody(), MediaType.APPLICATION_JSON));

    FrozenHold frozen = freezer.freeze(HOLD_ID, ORDER_ID, USER_ID);

    assertThat(frozen.holdId()).isEqualTo(HOLD_ID);
    assertThat(frozen.eventId()).isEqualTo(EVENT_ID);
    assertThat(frozen.userId()).isEqualTo(USER_ID);
    assertThat(frozen.seats())
        .containsExactly(
            new OrderItem("A-1-1", Money.of(4500L, "EUR")),
            new OrderItem("A-1-2", Money.of(4500L, "EUR")));
    assertThat(frozen.total()).isEqualTo(Money.of(9000L, "EUR"));
    inventory.verify();
  }

  /** A total inventory and this service disagree on is caught at the boundary. */
  @Test
  void refusesAnAnswerWhoseTotalDoesNotMatchItsLines() {
    respondWith(
        withSuccess(
            """
            {"holdId":"%s","eventId":"%s","userId":"%s","orderId":"%s","status":"CHECKOUT",
             "seats":[{"seatId":"A-1-1","sectionId":"A","price":{"amountCents":4500,"currency":"EUR"}}],
             "total":{"amountCents":9999,"currency":"EUR"}}
            """
                .formatted(HOLD_ID, EVENT_ID, USER_ID, ORDER_ID),
            MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("come to");
  }

  @Test
  void turnsAnExpiredHoldIntoItsOwnRefusal() {
    respondWith(problem(HttpStatus.GONE, "HOLD_EXPIRED", "This hold expired"));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(HoldExpiredException.class)
        .hasMessageContaining("This hold expired");
  }

  @Test
  void turnsAHoldOfAnotherOrderIntoItsOwnRefusal() {
    respondWith(problem(HttpStatus.CONFLICT, "HOLD_ALREADY_USED", "Another order has it"));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(HoldAlreadyUsedException.class)
        .hasMessageContaining("Another order has it");
  }

  @Test
  void turnsAnUnknownHoldIntoItsOwnRefusal() {
    respondWith(problem(HttpStatus.NOT_FOUND, "NOT_FOUND", "No hold with that id"));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(HoldNotFoundException.class);
  }

  @Test
  void turnsAHoldOfAnotherUserIntoItsOwnRefusal() {
    respondWith(problem(HttpStatus.FORBIDDEN, "FORBIDDEN", "Not your hold"));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(HoldNotOwnedException.class);
  }

  /**
   * The code and not the status decides. A refusal carrying a code this endpoint is not supposed to
   * answer with is not guessed at from its status, which here would have read as a lost race.
   */
  @Test
  void doesNotGuessAtACodeThisEndpointShouldNotAnswerWith() {
    respondWith(problem(HttpStatus.CONFLICT, "SEAT_UNAVAILABLE", "Seats are gone"));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(InventoryUnavailableException.class)
        .hasMessageContaining("SEAT_UNAVAILABLE");
  }

  @Test
  void doesNotGuessAtACodeThisBuildHasNeverHeardOf() {
    respondWith(problem(HttpStatus.CONFLICT, "HOLD_SOMETHING_NEW", "A newer inventory"));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(InventoryUnavailableException.class)
        .hasMessageContaining("no error code");
  }

  /** A proxy or a gateway answering instead of inventory, with a body that carries no code. */
  @Test
  void treatsARefusalWithoutACodeAsInventoryBeingUnusable() {
    respondWith(
        withStatus(HttpStatus.BAD_GATEWAY)
            .body("<html>no backend</html>")
            .contentType(MediaType.TEXT_HTML));

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(InventoryUnavailableException.class);
  }

  @Test
  void treatsAFailureOfInventoryAsRetryable() {
    respondWith(withServerError());

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(InventoryUnavailableException.class);
  }

  @Test
  void treatsAnAnswerWithNoBodyAsInventoryBeingUnusable() {
    respondWith(withSuccess());

    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, USER_ID))
        .isInstanceOf(InventoryUnavailableException.class)
        .hasMessageContaining("no body");
  }

  @Test
  void refusesToBeCalledWithoutWhatItNeeds() {
    assertThatThrownBy(() -> freezer.freeze(null, ORDER_ID, USER_ID))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, null, USER_ID))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> freezer.freeze(HOLD_ID, ORDER_ID, null))
        .isInstanceOf(NullPointerException.class);
  }

  private void respondWith(DefaultResponseCreator response) {
    inventory.expect(requestTo(FREEZE_URL)).andRespond(response);
  }

  private static DefaultResponseCreator problem(HttpStatus status, String code, String detail) {
    return withStatus(status)
        .contentType(MediaType.APPLICATION_PROBLEM_JSON)
        .body(
            """
            {"type":"urn:seatrush:error:x","title":"t","status":%d,"detail":"%s","code":"%s"}
            """
                .formatted(status.value(), detail, code));
  }

  private static String frozenBody() {
    return """
        {"holdId":"%s","eventId":"%s","userId":"%s","orderId":"%s","status":"CHECKOUT",
         "seats":[
           {"seatId":"A-1-1","sectionId":"A","price":{"amountCents":4500,"currency":"EUR"}},
           {"seatId":"A-1-2","sectionId":"A","price":{"amountCents":4500,"currency":"EUR"}}],
         "total":{"amountCents":9000,"currency":"EUR"}}
        """
        .formatted(HOLD_ID, EVENT_ID, USER_ID, ORDER_ID);
  }
}
