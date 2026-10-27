package com.seatrush.order.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.common.money.Money;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.order.application.CheckoutService;
import com.seatrush.order.application.OrderDetails;
import com.seatrush.order.application.OrderQueryService;
import com.seatrush.order.domain.HoldAlreadyUsedException;
import com.seatrush.order.domain.HoldExpiredException;
import com.seatrush.order.domain.HoldNotFoundException;
import com.seatrush.order.domain.HoldNotOwnedException;
import com.seatrush.order.domain.IdempotencyKeyReusedException;
import com.seatrush.order.domain.InventoryUnavailableException;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderNotFoundException;
import com.seatrush.order.domain.OrderNotOwnedException;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.order.domain.Ticket;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The HTTP contract of the order endpoints: the status codes and the error codes of section 12. */
@WebMvcTest(OrderController.class)
class OrderControllerTest {

  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID KEY = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  private static final UUID TICKET_ID = UUID.fromString("0199c5f0-7777-7000-8000-00000000f007");
  private static final Instant CREATED_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Money SEAT = Money.of(4500L, "EUR");

  @Autowired private MockMvc mvc;

  @MockitoBean private CheckoutService checkouts;
  @MockitoBean private OrderQueryService orders;

  /** A checkout is accepted and not created: the seats are not sold until the SAGA says so. */
  @Test
  void aCheckoutIsAcceptedAndPointsAtItself() throws Exception {
    given(checkouts.start(any())).willReturn(order(OrderStatus.PENDING, null));

    mvc.perform(startRequest())
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/orders/" + ORDER_ID))
        .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.holdId").value(HOLD_ID.toString()))
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.items.length()").value(2))
        .andExpect(jsonPath("$.items[0].seatId").value("A-1-1"))
        .andExpect(jsonPath("$.items[0].price.amountCents").value(4500))
        .andExpect(jsonPath("$.total.amountCents").value(9000))
        .andExpect(jsonPath("$.total.currency").value("EUR"))
        .andExpect(jsonPath("$.createdAt").value("2026-06-01T10:00:00Z"));
  }

  /** How the service recognises a retry is not part of the contract a client reads back. */
  @Test
  void neverExposesTheIdempotencyKeyOrTheRequestHash() throws Exception {
    given(checkouts.start(any())).willReturn(order(OrderStatus.PENDING, null));

    mvc.perform(startRequest())
        .andExpect(jsonPath("$.idempotencyKey").doesNotExist())
        .andExpect(jsonPath("$.requestHash").doesNotExist())
        .andExpect(jsonPath("$.userId").doesNotExist());
  }

  @Test
  void anExpiredHoldIsGone() throws Exception {
    willThrow(new HoldExpiredException(HOLD_ID, "expired")).given(checkouts).start(any());

    mvc.perform(startRequest())
        .andExpect(status().isGone())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"));
  }

  @Test
  void aHoldAnotherOrderOwnsIsAConflict() throws Exception {
    willThrow(new HoldAlreadyUsedException(HOLD_ID, "taken")).given(checkouts).start(any());

    mvc.perform(startRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOLD_ALREADY_USED"));
  }

  @Test
  void anUnknownHoldIsNotFound() throws Exception {
    willThrow(new HoldNotFoundException(HOLD_ID)).given(checkouts).start(any());

    mvc.perform(startRequest())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aHoldOfAnotherUserIsForbidden() throws Exception {
    willThrow(new HoldNotOwnedException(HOLD_ID)).given(checkouts).start(any());

    mvc.perform(startRequest())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void aReusedKeyIsUnprocessableAndNamesTheKey() throws Exception {
    willThrow(new IdempotencyKeyReusedException(KEY)).given(checkouts).start(any());

    mvc.perform(startRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"))
        .andExpect(jsonPath("$.idempotencyKey").value(KEY.toString()));
  }

  /** Retryable with the same key, which is the one thing the client has to be told here. */
  @Test
  void anInventoryThatCannotAnswerIsRetryable() throws Exception {
    willThrow(new InventoryUnavailableException("connection refused", null))
        .given(checkouts)
        .start(any());

    mvc.perform(startRequest())
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("TEMPORARILY_UNAVAILABLE"))
        .andExpect(jsonPath("$.detail").value(Matchers.containsString("Idempotency-Key")));
  }

  @Test
  void aBodyWithoutAHoldIsRejected() throws Exception {
    mvc.perform(
            post("/api/orders")
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", KEY)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void anIdempotencyKeyThatIsNotAUuidIsABadRequest() throws Exception {
    mvc.perform(
            post("/api/orders")
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void answersWithAnOrderToItsOwner() throws Exception {
    given(orders.get(any(), any()))
        .willReturn(OrderDetails.withoutTickets(order(OrderStatus.CONFIRMED, null)));

    mvc.perform(get("/api/orders/{orderId}", ORDER_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CONFIRMED"))
        .andExpect(jsonPath("$.failureReason").doesNotExist());
  }

  /** README section 12.5: a confirmed order carries what somebody takes to the gate. */
  @Test
  void aConfirmedOrderCarriesItsTickets() throws Exception {
    Order confirmed = order(OrderStatus.CONFIRMED, null);
    given(orders.get(any(), any()))
        .willReturn(
            new OrderDetails(
                confirmed,
                List.of(
                    new Ticket(
                        TICKET_ID,
                        ORDER_ID,
                        EVENT_ID,
                        "A-1-1",
                        "v1.payload.signature",
                        CREATED_AT))));

    mvc.perform(get("/api/orders/{orderId}", ORDER_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tickets", Matchers.hasSize(1)))
        .andExpect(jsonPath("$.tickets[0].ticketId").value(TICKET_ID.toString()))
        .andExpect(jsonPath("$.tickets[0].seatId").value("A-1-1"))
        .andExpect(jsonPath("$.tickets[0].signedPayload").value("v1.payload.signature"))
        .andExpect(jsonPath("$.tickets[0].issuedAt").value(CREATED_AT.toString()));
  }

  /**
   * Absent and not an empty array, which is the whole reason the field is {@code NON_NULL}: a
   * client watching a countdown can tell "not issued yet" from "issued, none".
   */
  @Test
  void anOrderStillCheckingOutCarriesNoTicketField() throws Exception {
    given(orders.get(any(), any()))
        .willReturn(OrderDetails.withoutTickets(order(OrderStatus.PENDING, null)));

    mvc.perform(get("/api/orders/{orderId}", ORDER_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.tickets").doesNotExist());
  }

  @Test
  void aFailedOrderSaysWhy() throws Exception {
    given(orders.get(any(), any()))
        .willReturn(
            OrderDetails.withoutTickets(order(OrderStatus.FAILED, FailureReason.PAYMENT_DECLINED)));

    mvc.perform(get("/api/orders/{orderId}", ORDER_ID).header("X-User-Id", USER_ID))
        .andExpect(jsonPath("$.status").value("FAILED"))
        .andExpect(jsonPath("$.failureReason").value("PAYMENT_DECLINED"));
  }

  @Test
  void anUnknownOrderIsNotFound() throws Exception {
    willThrow(new OrderNotFoundException(ORDER_ID)).given(orders).get(any(), any());

    mvc.perform(get("/api/orders/{orderId}", ORDER_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void anOrderOfAnotherUserIsForbidden() throws Exception {
    willThrow(new OrderNotOwnedException(ORDER_ID)).given(orders).get(any(), any());

    mvc.perform(get("/api/orders/{orderId}", ORDER_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void aPageOfOrdersCarriesTheCursorOfTheNextOne() throws Exception {
    given(orders.list(any(), any(), any())).willReturn(List.of(order(OrderStatus.PENDING, null)));

    mvc.perform(get("/api/orders").header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders.length()").value(1))
        .andExpect(jsonPath("$.nextBefore").value("2026-06-01T10:00:00Z"));
  }

  @Test
  void anEmptyPageCarriesNoCursor() throws Exception {
    given(orders.list(any(), any(), any())).willReturn(List.of());

    mvc.perform(get("/api/orders").header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.orders").isEmpty())
        .andExpect(jsonPath("$.nextBefore").doesNotExist());
  }

  @Test
  void aPageSizeThatIsNotPositiveIsRejected() throws Exception {
    mvc.perform(get("/api/orders").header("X-User-Id", USER_ID).param("limit", "0"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static MockHttpServletRequestBuilder startRequest() {
    return post("/api/orders")
        .header("X-User-Id", USER_ID)
        .header("Idempotency-Key", KEY)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body());
  }

  private static String body() {
    return "{\"holdId\":\"" + HOLD_ID + "\"}";
  }

  private static Order order(OrderStatus status, FailureReason reason) {
    return new Order(
        ORDER_ID,
        USER_ID,
        EVENT_ID,
        HOLD_ID,
        status,
        reason,
        List.of(new OrderItem("A-1-1", SEAT), new OrderItem("A-1-2", SEAT)),
        Money.of(9000L, "EUR"),
        KEY,
        "hash",
        CREATED_AT,
        CREATED_AT);
  }
}
