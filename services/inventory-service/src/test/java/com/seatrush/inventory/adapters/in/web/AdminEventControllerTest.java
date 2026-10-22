package com.seatrush.inventory.adapters.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.domain.Event;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.IllegalEventTransitionException;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(AdminEventController.class)
class AdminEventControllerTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  private static final String VALID_BODY =
      """
      {
        "name": "Final",
        "onSaleAt": "2026-06-01T10:00:00Z",
        "sections": [
          {"id": "A", "name": "Floor", "priceCents": 9000, "currency": "EUR",
           "rows": 20, "seatsPerRow": 50}
        ]
      }
      """;

  @Autowired private MockMvc mvc;

  @MockitoBean private EventCatalogService catalog;

  @Test
  void createsAnEventAndPointsAtIt() throws Exception {
    given(catalog.create(any())).willReturn(event(EventStatus.DRAFT));

    mvc.perform(
            post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/events/" + EVENT_ID))
        .andExpect(jsonPath("$.id").value(EVENT_ID.toString()))
        .andExpect(jsonPath("$.status").value("DRAFT"));
  }

  @Test
  void passesTheRequestedLayoutToTheUseCase() throws Exception {
    given(catalog.create(any())).willReturn(event(EventStatus.DRAFT));

    mvc.perform(
            post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(VALID_BODY))
        .andExpect(status().isCreated());

    ArgumentCaptor<CreateEventCommand> command = ArgumentCaptor.forClass(CreateEventCommand.class);
    verify(catalog).create(command.capture());
    assertCommand(command.getValue());
  }

  @Test
  void schedulesAndOpensAnEvent() throws Exception {
    given(catalog.schedule(EVENT_ID)).willReturn(event(EventStatus.SCHEDULED));
    given(catalog.openForSale(EVENT_ID)).willReturn(event(EventStatus.ON_SALE));

    mvc.perform(post("/api/admin/events/{id}/schedule", EVENT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    mvc.perform(post("/api/admin/events/{id}/open", EVENT_ID))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ON_SALE"));
  }

  @Test
  void aTransitionTheEventCannotMakeCarriesBothStatuses() throws Exception {
    willThrow(new IllegalEventTransitionException(EVENT_ID, EventStatus.DRAFT, EventStatus.ON_SALE))
        .given(catalog)
        .openForSale(EVENT_ID);

    mvc.perform(post("/api/admin/events/{id}/open", EVENT_ID))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.currentStatus").value("DRAFT"))
        .andExpect(jsonPath("$.requestedStatus").value("ON_SALE"));
  }

  @Test
  void refusesARequestWithoutSections() throws Exception {
    String body =
        """
        {"name": "Final", "onSaleAt": "2026-06-01T10:00:00Z", "sections": []}
        """;

    mvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fields.sections").exists());
  }

  @Test
  void refusesASectionThatIsNotAVenue() throws Exception {
    String body =
        """
        {
          "name": "Final",
          "onSaleAt": "2026-06-01T10:00:00Z",
          "sections": [
            {"id": "lower case", "name": "", "priceCents": 0, "currency": "eur",
             "rows": 0, "seatsPerRow": 100000}
          ]
        }
        """;

    mvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.fields['sections[0].id']").exists())
        .andExpect(jsonPath("$.fields['sections[0].name']").exists())
        .andExpect(jsonPath("$.fields['sections[0].priceCents']").exists())
        .andExpect(jsonPath("$.fields['sections[0].currency']").exists())
        .andExpect(jsonPath("$.fields['sections[0].rows']").exists())
        .andExpect(jsonPath("$.fields['sections[0].seatsPerRow']").exists());
  }

  @Test
  void refusesABodyItCannotRead() throws Exception {
    mvc.perform(post("/api/admin/events").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
  }

  private static void assertCommand(CreateEventCommand command) {
    assertThat(command.name()).isEqualTo("Final");
    assertThat(command.onSaleAt()).isEqualTo(ON_SALE_AT);
    assertThat(command.sections())
        .singleElement()
        .satisfies(
            section -> {
              assertThat(section.id()).isEqualTo("A");
              assertThat(section.price().amountCents()).isEqualTo(9000L);
              assertThat(section.seatCount()).isEqualTo(1_000);
            });
  }

  private static Event event(EventStatus status) {
    return new Event(EVENT_ID, "Final", ON_SALE_AT, status, Instant.parse("2026-05-01T09:00:00Z"));
  }
}
