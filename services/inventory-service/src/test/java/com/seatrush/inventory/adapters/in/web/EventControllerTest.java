package com.seatrush.inventory.adapters.in.web;

import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.EventDetails;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventNotFoundException;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.Section;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
class EventControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Instant CREATED_AT = Instant.parse("2026-05-01T09:00:00Z");

  @Autowired private MockMvc mvc;

  @MockitoBean private EventCatalogService catalog;

  @Test
  void listsTheCatalog() throws Exception {
    given(catalog.list()).willReturn(List.of(event(EventStatus.ON_SALE)));

    mvc.perform(get("/api/events"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
        .andExpect(jsonPath("$[0].id").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$[0].name").value("Final"))
        .andExpect(jsonPath("$[0].status").value("ON_SALE"))
        .andExpect(jsonPath("$[0].onSaleAt").value("2026-06-01T10:00:00Z"));
  }

  @Test
  void anEmptyCatalogIsAnEmptyArray() throws Exception {
    given(catalog.list()).willReturn(List.of());

    mvc.perform(get("/api/events")).andExpect(status().isOk()).andExpect(content().json("[]"));
  }

  @Test
  void readsOneEventWithItsSectionsAndPrices() throws Exception {
    given(catalog.details(EVENT_ID))
        .willReturn(
            new EventDetails(
                event(EventStatus.SCHEDULED),
                List.of(new Section(EVENT_ID, "A", "Floor", Money.of(4500L, "EUR"))),
                2_000));

    mvc.perform(get("/api/events/{id}", EVENT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.seatCount").value(2_000))
        .andExpect(jsonPath("$.sections[0].id").value("A"))
        .andExpect(jsonPath("$.sections[0].price.amountCents").value(4500))
        .andExpect(jsonPath("$.sections[0].price.currency").value("EUR"))
        .andExpect(jsonPath("$.sections[0].price.length()").value(2));
  }

  @Test
  void anUnknownEventIsAProblemDocumentWithTheStableCode() throws Exception {
    willThrow(new EventNotFoundException(EVENT_ID)).given(catalog).details(EVENT_ID);

    mvc.perform(get("/api/events/{id}", EVENT_ID))
        .andExpect(status().isNotFound())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("NOT_FOUND"))
        .andExpect(jsonPath("$.detail").value("No event with id " + EVENT_ID));
  }

  @Test
  void anIdThatIsNotAUuidIsARequestError() throws Exception {
    mvc.perform(get("/api/events/{id}", "not-a-uuid"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static Event event(EventStatus status) {
    return new Event(EVENT_ID, "Final", ON_SALE_AT, status, CREATED_AT);
  }
}
