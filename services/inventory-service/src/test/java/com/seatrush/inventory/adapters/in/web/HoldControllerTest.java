package com.seatrush.inventory.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.inventory.application.HoldService;
import com.seatrush.inventory.domain.EventNotOnSaleException;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Hold;
import com.seatrush.inventory.domain.HoldNoLongerActiveException;
import com.seatrush.inventory.domain.HoldNotFoundException;
import com.seatrush.inventory.domain.HoldNotOwnedException;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.HoldStatus;
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

/** The HTTP contract of the hold endpoints: the status codes and the error codes of section 12. */
@WebMvcTest(HoldController.class)
class HoldControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final Instant EXPIRES_AT = Instant.parse("2026-06-01T10:05:00Z");
  private static final String TWO_SEATS = "{\"seatIds\":[\"A-1-1\",\"A-1-2\"]}";

  @Autowired private MockMvc mvc;

  @MockitoBean private HoldService holds;

  @Test
  void aHoldIsCreatedAndPointsAtItself() throws Exception {
    given(holds.place(any())).willReturn(new HoldResult.Held(hold(HoldStatus.ACTIVE), 41L));

    mvc.perform(placeRequest())
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/holds/" + HOLD_ID))
        .andExpect(jsonPath("$.holdId").value(HOLD_ID.toString()))
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.seatIds").isArray())
        .andExpect(jsonPath("$.expiresAt").value("2026-06-01T10:05:00Z"))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").value(41));
  }

  @Test
  void aLostRaceNamesTheSeatsThatWereTaken() throws Exception {
    given(holds.place(any())).willReturn(new HoldResult.Conflict(List.of("A-1-1")));

    mvc.perform(placeRequest())
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("SEAT_UNAVAILABLE"))
        .andExpect(jsonPath("$.conflictingSeatIds[0]").value("A-1-1"));
  }

  @Test
  void aUserWhoAlreadyHoldsSeatsIsToldWhichHold() throws Exception {
    given(holds.place(any())).willReturn(new HoldResult.LimitReached(HOLD_ID));

    mvc.perform(placeRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOLD_LIMIT_REACHED"))
        .andExpect(jsonPath("$.holdId").value(HOLD_ID.toString()));
  }

  @Test
  void seatsThisEventDoesNotHaveAreARequestError() throws Exception {
    given(holds.place(any())).willReturn(new HoldResult.UnknownSeats(List.of("Z-9-9")));

    mvc.perform(placeRequest())
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.unknownSeatIds[0]").value("Z-9-9"));
  }

  @Test
  void anIdempotencyKeyUsedForSomethingElseIsRefused() throws Exception {
    given(holds.place(any())).willReturn(new HoldResult.IdempotencyKeyReused());

    mvc.perform(placeRequest())
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));
  }

  @Test
  void holdingSeatsOfASaleThatIsNotOpenIsRefused() throws Exception {
    willThrow(new EventNotOnSaleException(EVENT_ID, EventStatus.SCHEDULED))
        .given(holds)
        .place(any());

    mvc.perform(placeRequest())
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"))
        .andExpect(jsonPath("$.eventStatus").value("SCHEDULED"));
  }

  @Test
  void aRequestWithoutTheIdempotencyKeyOrTheUserIsRefused() throws Exception {
    mvc.perform(
            post("/api/events/{id}/holds", EVENT_ID)
                .header("X-User-Id", USER_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content(TWO_SEATS))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/events/{id}/holds", EVENT_ID)
                .header("Idempotency-Key", "a-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content(TWO_SEATS))
        .andExpect(status().isBadRequest());
  }

  @Test
  void aRequestWithoutSeatsIsRefused() throws Exception {
    mvc.perform(
            post("/api/events/{id}/holds", EVENT_ID)
                .header("X-User-Id", USER_ID)
                .header("Idempotency-Key", "a-key")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"seatIds\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  @Test
  void aHoldIsReadByItsOwner() throws Exception {
    given(holds.get(HOLD_ID, USER_ID)).willReturn(hold(HoldStatus.ACTIVE));

    mvc.perform(get("/api/holds/{id}", HOLD_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.holdId").value(HOLD_ID.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.version").doesNotExist());
  }

  @Test
  void aHoldOfSomebodyElseIsForbiddenAndAMissingOneIsNotFound() throws Exception {
    willThrow(new HoldNotOwnedException(HOLD_ID)).given(holds).get(HOLD_ID, USER_ID);

    mvc.perform(get("/api/holds/{id}", HOLD_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("FORBIDDEN"));

    UUID other = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
    willThrow(new HoldNotFoundException(other)).given(holds).get(other, USER_ID);

    mvc.perform(get("/api/holds/{id}", other).header("X-User-Id", USER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void releasingAHoldAnswersNoContent() throws Exception {
    mvc.perform(delete("/api/holds/{id}", HOLD_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isNoContent());

    verify(holds).release(HOLD_ID, USER_ID);
  }

  @Test
  void aHoldInCheckoutCannotBeReleasedByItsUser() throws Exception {
    willThrow(new HoldNoLongerActiveException(HOLD_ID, HoldStatus.CHECKOUT))
        .given(holds)
        .release(HOLD_ID, USER_ID);

    mvc.perform(delete("/api/holds/{id}", HOLD_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("HOLD_ALREADY_USED"))
        .andExpect(jsonPath("$.holdStatus").value("CHECKOUT"));
  }

  @Test
  void aHoldThatIsAlreadyGoneIsReportedAsGone() throws Exception {
    willThrow(new HoldNoLongerActiveException(HOLD_ID, HoldStatus.EXPIRED))
        .given(holds)
        .release(HOLD_ID, USER_ID);

    mvc.perform(delete("/api/holds/{id}", HOLD_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isGone())
        .andExpect(jsonPath("$.code").value("HOLD_EXPIRED"))
        .andExpect(jsonPath("$.holdStatus").value("EXPIRED"));
  }

  private static MockHttpServletRequestBuilder placeRequest() {
    return post("/api/events/{id}/holds", EVENT_ID)
        .header("X-User-Id", USER_ID)
        .header("Idempotency-Key", "a-key")
        .contentType(MediaType.APPLICATION_JSON)
        .content(TWO_SEATS);
  }

  private static Hold hold(HoldStatus status) {
    // Only the statuses this controller shows are built here, and none of them is a checkout.
    return new Hold(
        HOLD_ID, EVENT_ID, USER_ID, List.of("A-1-1", "A-1-2"), EXPIRES_AT, status, null);
  }
}
