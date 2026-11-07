package com.seatrush.inventory.adapters.in.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.inventory.application.SeatMapService;
import com.seatrush.inventory.domain.EventNotFoundException;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * The HTTP contract of the rebuild endpoint of README section 12.7.
 *
 * <p>Short, because the endpoint is: it is one call to the warm-up the sale opening already makes,
 * and what is worth asserting is that the path is the one section 12.7 publishes, that it answers
 * with the size of what it loaded, and that an unknown event is a 404 rather than a 500. The
 * rebuild itself is covered where the warm-up is.
 */
@WebMvcTest(InternalEventController.class)
class InternalEventControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  @Autowired private MockMvc mvc;

  @MockitoBean private SeatMapService seatMaps;

  @Test
  void rebuildsTheSeatMapAndSaysHowBigItIs() throws Exception {
    given(seatMaps.warmUp(EVENT_ID)).willReturn(20_000);

    mvc.perform(post("/internal/events/{eventId}/rebuild", EVENT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.eventId").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.seats").value(20_000));

    then(seatMaps).should().warmUp(EVENT_ID);
  }

  @Test
  void anUnknownEventIsNotFound() throws Exception {
    willThrow(new EventNotFoundException(EVENT_ID)).given(seatMaps).warmUp(EVENT_ID);

    mvc.perform(post("/internal/events/{eventId}/rebuild", EVENT_ID))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }
}
