package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.domain.EventStatus;
import com.seatrush.inventory.domain.IllegalEventTransitionException;
import com.seatrush.inventory.domain.Seat;
import com.seatrush.testsupport.containers.PostgresSupport;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The catalog against a real Postgres: the migration, the generated venue and the lifecycle, from
 * the HTTP request down to the rows.
 *
 * <p>It runs on its own database on the shared container, so the Flyway migration of this service
 * is the only schema in it, exactly as in production (README section 11).
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfDockerAvailable
class CatalogIT {

  private static final String DATABASE = "inventory_catalog_it";

  private static final String DEMO_VENUE =
      """
      {
        "name": "Champions Final",
        "onSaleAt": "2026-06-01T10:00:00Z",
        "sections": [
          {"id": "A", "name": "Floor", "priceCents": 9000, "currency": "EUR",
           "rows": 20, "seatsPerRow": 50},
          {"id": "B", "name": "Lower tier", "priceCents": 6000, "currency": "EUR",
           "rows": 20, "seatsPerRow": 30},
          {"id": "C", "name": "Upper tier", "priceCents": 3000, "currency": "EUR",
           "rows": 10, "seatsPerRow": 40}
        ]
      }
      """;

  @Autowired private MockMvc mvc;
  @Autowired private EventCatalogService catalog;
  @Autowired private JdbcClient jdbc;

  /**
   * Creates the database before the context starts.
   *
   * <p>The work happens here rather than in a {@code @BeforeAll}, because the datasource is built
   * while the context is prepared, which is earlier: by the time a lifecycle callback could run,
   * Flyway would already have failed to connect.
   */
  @DynamicPropertySource
  static void inventoryDatabase(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
  }

  @AfterEach
  void emptyTheCatalog() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event CASCADE").update();
  }

  @Test
  void createsAnEventWithTwoThousandSeatsAndReadsItBack() throws Exception {
    UUID eventId = createDemoEvent();

    mvc.perform(get("/api/events/{id}", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.name").value("Champions Final"))
        .andExpect(jsonPath("$.status").value("DRAFT"))
        .andExpect(jsonPath("$.seatCount").value(2_000))
        .andExpect(jsonPath("$.sections.length()").value(3))
        .andExpect(jsonPath("$.sections[0].id").value("A"))
        .andExpect(jsonPath("$.sections[0].price.amountCents").value(9000))
        .andExpect(jsonPath("$.sections[2].price.amountCents").value(3000));
  }

  @Test
  void theGeneratedVenueIsStoredSeatBySeat() {
    UUID eventId = createDemoEvent();

    List<Seat> seats = catalog.seatsOf(eventId);

    assertThat(seats).hasSize(2_000).extracting(Seat::id).doesNotHaveDuplicates();
    assertThat(seats.getFirst().id())
        .as("seat map order starts at the first row")
        .isEqualTo("A-1-1");
    assertThat(seats).extracting(Seat::sectionId).containsOnly("A", "B", "C");
    assertThat(jdbc.sql("SELECT count(*) FROM seat").query(Integer.class).single())
        .isEqualTo(2_000);
    assertThat(jdbc.sql("SELECT count(*) FROM section").query(Integer.class).single()).isEqualTo(3);
  }

  @Test
  void rowsComeBackInNumericOrderAndNotAlphabetically() {
    UUID eventId = createDemoEvent();

    List<String> rowsOfSectionA =
        catalog.seatsOf(eventId).stream()
            .filter(seat -> seat.sectionId().equals("A"))
            .map(Seat::rowLabel)
            .distinct()
            .toList();

    assertThat(rowsOfSectionA).startsWith("1", "2", "3").endsWith("19", "20");
  }

  @Test
  void listsTheEventsOfTheCatalog() throws Exception {
    UUID eventId = createDemoEvent();

    mvc.perform(get("/api/events"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].id").value(eventId.toString()));
  }

  @Test
  void movesAnEventThroughItsLifecycleAndStoresEachStep() throws Exception {
    UUID eventId = createDemoEvent();

    mvc.perform(post("/api/admin/events/{id}/schedule", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SCHEDULED"));
    assertThat(storedStatus(eventId)).isEqualTo("SCHEDULED");

    mvc.perform(post("/api/admin/events/{id}/open", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ON_SALE"));
    assertThat(storedStatus(eventId)).isEqualTo("ON_SALE");
  }

  @Test
  void aSaleCannotBeOpenedBeforeTheEventIsAnnounced() throws Exception {
    UUID eventId = createDemoEvent();

    mvc.perform(post("/api/admin/events/{id}/open", eventId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.currentStatus").value("DRAFT"));

    assertThat(storedStatus(eventId)).isEqualTo("DRAFT");
  }

  @Test
  void anUnknownEventIsNotFound() throws Exception {
    UUID unknown = UUID.fromString("0199c5f0-9999-7000-8000-00000000dead");

    mvc.perform(get("/api/events/{id}", unknown))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    mvc.perform(post("/api/admin/events/{id}/schedule", unknown))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  /**
   * Many callers opening the same sale at once, which is what a demo and an admission burst both
   * look like.
   *
   * <p>Exactly one of them performs the transition, because the update carries the expected status
   * (README section 21 asks for one test of this kind against a real database).
   */
  @Test
  void onlyOneOfManyConcurrentCallersOpensTheSale() throws Exception {
    UUID eventId = createDemoEvent();
    catalog.schedule(eventId);
    int callers = 50;

    List<Callable<Boolean>> attempts =
        IntStream.range(0, callers)
            .<Callable<Boolean>>mapToObj(
                i ->
                    () -> {
                      try {
                        catalog.openForSale(eventId);
                        return true;
                      } catch (IllegalEventTransitionException refused) {
                        return false;
                      }
                    })
            .toList();

    List<Future<Boolean>> results;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      results = executor.invokeAll(attempts);
    }

    long winners = 0;
    for (Future<Boolean> result : results) {
      if (result.get()) {
        winners++;
      }
    }
    assertThat(winners).isEqualTo(1);
    assertThat(storedStatus(eventId)).isEqualTo(EventStatus.ON_SALE.name());
  }

  private UUID createDemoEvent() {
    try {
      MvcResult created =
          mvc.perform(
                  post("/api/admin/events")
                      .contentType(MediaType.APPLICATION_JSON)
                      .content(DEMO_VENUE))
              .andExpect(status().isCreated())
              .andReturn();
      String location = created.getResponse().getHeader("Location");
      assertThat(location).as("the created event points at itself").isNotNull();
      return UUID.fromString(location.substring(location.lastIndexOf('/') + 1));
    } catch (Exception e) {
      throw new AssertionError("could not create the demo event", e);
    }
  }

  private String storedStatus(UUID eventId) {
    return jdbc.sql("SELECT status FROM event WHERE id = :id")
        .param("id", eventId)
        .query(String.class)
        .single();
  }
}
