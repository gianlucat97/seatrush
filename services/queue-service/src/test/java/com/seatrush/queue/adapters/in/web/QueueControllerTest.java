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
import com.seatrush.queue.application.WaitingPlace;
import com.seatrush.queue.domain.EventNotFoundException;
import com.seatrush.queue.domain.EventNotOnSaleException;
import com.seatrush.queue.domain.QueuePlace;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
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

  /**
   * The clock the admission signer needs.
   *
   * <p>A web slice does not load {@code seatrush-common}, which contributes the clock every service
   * injects, but it does load what this application imports, and the imported admission signer is
   * built from one. Nothing in this test reads it: the signing happens a layer below.
   */
  @TestConfiguration
  static class WithAClock {

    @Bean
    Clock clock() {
      return Clock.systemUTC();
    }
  }

  @BeforeEach
  void aKnownEvent() {
    given(calendar.find(EVENT_ID))
        .willReturn(Optional.of(new SaleEvent(EVENT_ID, "A sale", ON_SALE_AT, SaleStatus.ON_SALE)));
  }

  @Test
  void answersAJoinWithThePlaceTheWaitAndTheCountdown() throws Exception {
    given(queue.join(EVENT_ID, USER_ID)).willReturn(queued(1_200, 200, 42L));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.state").value("QUEUED"))
        .andExpect(jsonPath("$.position").value(1_000))
        .andExpect(jsonPath("$.estimatedWaitSeconds").value(42))
        .andExpect(jsonPath("$.onSaleAt").value("2026-06-01T10:00:00Z"))
        .andExpect(jsonPath("$.admissionToken").doesNotExist())
        .andExpect(jsonPath("$.admissionExpiresAt").doesNotExist());
  }

  /** A lobby member has no position and nothing to wait through: both fields are absent. */
  @Test
  void leavesThePositionOutForALobbyMember() throws Exception {
    given(queue.join(EVENT_ID, USER_ID))
        .willReturn(WaitingPlace.of(QueuePlace.inLobby(EVENT_ID, USER_ID)));

    mvc.perform(post("/api/events/{id}/queue", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("LOBBY"))
        .andExpect(jsonPath("$.position").doesNotExist())
        .andExpect(jsonPath("$.estimatedWaitSeconds").doesNotExist());
  }

  /** A buyer through the door: the token and the countdown, and nothing left to wait for. */
  @Test
  void answersAnAdmittedBuyerWithTheirAdmission() throws Exception {
    QueuePlace admitted =
        QueuePlace.withSequence(EVENT_ID, USER_ID, 50, 50, Instant.parse("2026-06-01T10:10:00Z"));
    given(queue.place(EVENT_ID, USER_ID))
        .willReturn(Optional.of(new WaitingPlace(admitted, null, "v1.admission.token")));

    mvc.perform(get("/api/events/{id}/queue/me", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ADMITTED"))
        .andExpect(jsonPath("$.position").doesNotExist())
        .andExpect(jsonPath("$.estimatedWaitSeconds").doesNotExist())
        .andExpect(jsonPath("$.admissionToken").value("v1.admission.token"))
        .andExpect(jsonPath("$.admissionExpiresAt").value("2026-06-01T10:10:00Z"));
  }

  /**
   * The buyer whose time ran out. Admitted with no token is the one case a client has to handle,
   * and the absence of the token is the signal to re-join rather than to retry.
   */
  @Test
  void answersALapsedBuyerWithNoTokenAtAll() throws Exception {
    given(queue.place(EVENT_ID, USER_ID))
        .willReturn(
            Optional.of(WaitingPlace.of(QueuePlace.withSequence(EVENT_ID, USER_ID, 50, 50, null))));

    mvc.perform(get("/api/events/{id}/queue/me", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("ADMITTED"))
        .andExpect(jsonPath("$.admissionToken").doesNotExist())
        .andExpect(jsonPath("$.admissionExpiresAt").doesNotExist());
  }

  /** The sequence is an implementation detail of a position and is not part of the contract. */
  @Test
  void neverPublishesTheSequenceNumber() throws Exception {
    given(queue.join(EVENT_ID, USER_ID)).willReturn(queued(1_200, 200, 42L));

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

  private static WaitingPlace queued(long sequence, long head, Long estimatedWaitSeconds) {
    return new WaitingPlace(
        QueuePlace.withSequence(EVENT_ID, USER_ID, sequence, head, null),
        estimatedWaitSeconds,
        null);
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
    given(queue.place(EVENT_ID, USER_ID)).willReturn(Optional.of(queued(10, 0, 1L)));

    mvc.perform(get("/api/events/{id}/queue/me", EVENT_ID).header("X-User-Id", USER_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.position").value(10))
        .andExpect(jsonPath("$.onSaleAt").doesNotExist());
  }
}
