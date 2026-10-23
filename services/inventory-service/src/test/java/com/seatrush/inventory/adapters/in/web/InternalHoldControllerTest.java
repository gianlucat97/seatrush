package com.seatrush.inventory.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.FrozenHold;
import com.seatrush.inventory.application.HoldFreezeService;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldAlreadyFrozenException;
import com.seatrush.inventory.domain.HoldExpiredException;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.HoldStatus;
import com.seatrush.inventory.domain.SeatPrice;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * The HTTP contract of the internal freeze endpoint: the error codes of section 12.6 that
 * order-service branches on.
 */
@WebMvcTest(InternalHoldController.class)
class InternalHoldControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T10:05:00Z");

  @Autowired private MockMvc mvc;

  @MockitoBean private HoldFreezeService freezes;

  @Test
  void aFrozenHoldAnswersWithItsSeatsAndTheirPrices() throws Exception {
    given(freezes.freeze(any())).willReturn(frozenHold());

    mvc.perform(freezeRequest())
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdId").value(HOLD_ID.toString()))
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.userId").value(USER_ID.toString()))
        .andExpect(jsonPath("$.orderId").value(ORDER_ID.toString()))
        .andExpect(jsonPath("$.status").value("CHECKOUT"))
        .andExpect(jsonPath("$.seats.length()").value(2))
        .andExpect(jsonPath("$.seats[0].seatId").value("A-1-1"))
        .andExpect(jsonPath("$.seats[0].sectionId").value("A"))
        .andExpect(jsonPath("$.seats[0].price.amountCents").value(4500))
        .andExpect(jsonPath("$.seats[0].price.currency").value("EUR"))
        .andExpect(jsonPath("$.total.amountCents").value(9000))
        .andExpect(jsonPath("$.total.currency").value("EUR"));
  }

  @Test
  void anExpiredHoldIsGoneAndSaysWhen() throws Exception {
    willThrow(new HoldExpiredException(HOLD_ID, EXPIRES_AT)).given(freezes).freeze(any());

    mvc.perform(freezeRequest())
        .andExpect(status().isGone())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"))
        .andExpect(jsonPath("$.expiresAt").value("2026-06-01T10:05:00Z"));
  }

  @Test
  void aHoldAnotherOrderFrozeNamesThatOrder() throws Exception {
    UUID other = UUID.fromString("0199c5f0-9999-7000-8000-00000000f009");
    willThrow(new HoldAlreadyFrozenException(HOLD_ID, other)).given(freezes).freeze(any());

    mvc.perform(freezeRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOLD_ALREADY_USED"))
        .andExpect(jsonPath("$.orderId").value(other.toString()));
  }

  @Test
  void aConsumedHoldIsAlreadyUsed() throws Exception {
    willThrow(new HoldNoLongerActiveException(HOLD_ID, HoldStatus.CONSUMED))
        .given(freezes)
        .freeze(any());

    mvc.perform(freezeRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOLD_ALREADY_USED"))
        .andExpect(jsonPath("$.holdStatus").value("CONSUMED"));
  }

  @Test
  void aReleasedHoldIsGone() throws Exception {
    willThrow(new HoldNoLongerActiveException(HOLD_ID, HoldStatus.RELEASED))
        .given(freezes)
        .freeze(any());

    mvc.perform(freezeRequest())
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"))
        .andExpect(jsonPath("$.holdStatus").value("RELEASED"));
  }

  @Test
  void anUnknownHoldIsNotFound() throws Exception {
    willThrow(new HoldNotFoundException(HOLD_ID)).given(freezes).freeze(any());

    mvc.perform(freezeRequest())
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aHoldOfAnotherUserIsForbidden() throws Exception {
    willThrow(new HoldNotOwnedException(HOLD_ID)).given(freezes).freeze(any());

    mvc.perform(freezeRequest())
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));
  }

  @Test
  void aBodyWithoutAnOrderIsRejectedBeforeTheServiceIsCalled() throws Exception {
    mvc.perform(
            post("/internal/holds/{holdId}/freeze", HOLD_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":\"" + USER_ID + "\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aHoldIdThatIsNotAUuidIsABadRequest() throws Exception {
    mvc.perform(
            post("/internal/holds/{holdId}/freeze", "not-a-uuid")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body()))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static MockHttpServletRequestBuilder freezeRequest() {
    return post("/internal/holds/{holdId}/freeze", HOLD_ID)
        .contentType(MediaType.APPLICATION_JSON)
        .content(body());
  }

  private static String body() {
    return "{\"orderId\":\"" + ORDER_ID + "\",\"userId\":\"" + USER_ID + "\"}";
  }

  private static FrozenHold frozenHold() {
    Hold hold =
        new Hold(
            HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1", "A-1-2"), EXPIRES_AT, HoldStatus.CHECKOUT);
    return new FrozenHold(
        hold,
        ORDER_ID,
        List.of(
            new SeatPrice("A-1-1", "A", Money.of(4500L, "EUR")),
            new SeatPrice("A-1-2", "A", Money.of(4500L, "EUR"))),
        Money.of(9000L, "EUR"));
  }
}
