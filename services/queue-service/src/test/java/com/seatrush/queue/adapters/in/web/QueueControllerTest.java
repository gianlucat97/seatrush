package com.seatrush.queue.adapters.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The three endpoints as HTTP: the statuses, the bodies, and the stable error codes of README
 * section 12.6.
 */
@WebMvcTest(controllers = QueueController.class)
class QueueControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  @Autowired private MockMvc mvc;
  @MockitoBean private QueueService queue;
  @MockitoBean private SaleCalendar calendar;

  @BeforeEach
  void aKnownEvent() {
    given(calendar.find(EVENT_ID))
        .willReturn(Optional.of(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.ON_SALE)));
  }

  @Test
  void answersAJoinWithThePlaceAndTheCountdown() throws Exception {
    given(queue.join(EVENT_ID, USER_ID))
        .willReturn(QueuePlace.withSequence(EVENT_ID, USER_ID, 1_200, 200));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.state").value("QUEUED"))
        .andExpect(jsonPath("$.position").value(1_000))
        .andExpect(jsonPath("$.onSaleAt").value("2026-06-01T10:00:00Z"));
  }

  /** A lobby member has no position, and the field is absent rather than zero. */
  @Test
  void leavesThePositionOutForALobbyMember() throws Exception {
    given(queue.join(EVENT_ID, USER_ID)).willReturn(QueuePlace.inLobby(EVENT_ID, USER_ID));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("LOBBY"))
        .andExpect(jsonPath("$.position").doesNotExist());
  }

  @Test
  void leavesThePositionOutForAnAdmittedUser() throws Exception {
    given(queue.place(EVENT_ID, USER_ID))
        .willReturn(Optional.of(QueuePlace.withSequence(EVENT_ID, USER_ID, 50, 50)));

    mvc.perform(get("/api/events/{id}/queue/me", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ADMITTED"))
        .andExpect(jsonPath("$.position").doesNotExist());
  }

  /** The sequence is an implementation detail of a position and is not part of the contract. */
  @Test
  void neverPublishesTheSequenceNumber() throws Exception {
    given(queue.join(EVENT_ID, USER_ID))
        .willReturn(QueuePlace.withSequence(EVENT_ID, USER_ID, 1_200, 200));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(jsonPath("$.sequence").doesNotExist());
  }

  @Test
  void refusesAJoinForAnUnknownEventWithTheStableCode() throws Exception {
    given(queue.join(EVENT_ID, USER_ID)).willThrow(new EventNotFoundException(EVENT_ID));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void refusesAJoinForASaleThatIsOverAndSaysWhichStatus() throws Exception {
    given(queue.join(EVENT_ID, USER_ID))
        .willThrow(new EventNotOnSaleException(EVENT_ID, SaleStatus.SOLD_OUT));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"))
        .andExpect(jsonPath("$.eventStatus").value("SOLD_OUT"));
  }

  @Test
  void answersAPlaceAskedForByNobodyWaitingWithANotFound() throws Exception {
    given(queue.place(EVENT_ID, USER_ID)).willReturn(Optional.empty());

    mvc.perform(get("/api/events/{id}/queue/me", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /**
   * 204 whether or not there was anything to leave: a client leaving a queue is saying what it
   * wants the state to be, and that state is reached either way.
   */
  @Test
  void answersALeaveTheSameWayTwice() throws Exception {
    given(queue.leave(EVENT_ID, USER_ID)).willReturn(true, false);

    mvc.perform(delete("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isNoContent());
    mvc.perform(delete("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isNoContent());

    verify(queue, times(2)).leave(EVENT_ID, USER_ID);
  }

  @Test
  void refusesARequestWithoutTheUserHeader() throws Exception {
    mvc.perform(post("/api/events/{id}/queue", EVENT_ID)).andExpect(status().isBadRequest());
  }

  @Test
  void refusesAnEventIdThatIsNotAnIdWithTheStableCode() throws Exception {
    mvc.perform(post("/api/events/{id}/queue", "not-an-id").header("X-User-Id", USER_ID))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  /** A place is still served when the countdown cannot be: the rest of the answer is useful. */
  @Test
  void answersWithoutASaleTimeForAnEventTheCalendarHasLost() throws Exception {
    given(calendar.find(any())).willReturn(Optional.empty());
    given(queue.place(EVENT_ID, USER_ID))
        .willReturn(Optional.of(QueuePlace.withSequence(EVENT_ID, USER_ID, 10, 0)));

    mvc.perform(get("/api/events/{id}/queue/me", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.position").value(10))
        .andExpect(jsonPath("$.onSaleAt").doesNotExist());
  }
}
