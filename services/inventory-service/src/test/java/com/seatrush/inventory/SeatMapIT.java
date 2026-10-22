package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.SeatMapService;
import com.seatrush.inventory.application.SeatMapSnapshot;
import com.seatrush.inventory.domain.SeatStatus;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The warm-up and the seat map snapshot against a real Postgres and a real Redis.
 *
 * <p>This is the first test of the two stores working together, which is the whole point of the
 * design: Postgres owns the venue and the sales, Redis owns the sale in progress, and the snapshot
 * is the join. The keys asserted here are the ones the Lua scripts of the hold path will share.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfDockerAvailable
class SeatMapIT {

  private static final String DATABASE = "inventory_seatmap_it";

  @Autowired private MockMvc mvc;
  @Autowired private EventCatalogService events;
  @Autowired private SeatMapService seatMap;
  @Autowired private JdbcClient jdbc;
  @Autowired private StringRedisTemplate redis;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    RedisSupport.registerProperties(registry);
  }

  @AfterEach
  void emptyBothStores() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event CASCADE").update();
    RedisSupport.flushAll();
  }

  @Test
  void openingASaleLoadsTheWholeVenueIntoRedis() {
    UUID eventId = anEventOf(20, 50);
    events.schedule(eventId);

    events.openForSale(eventId);

    assertThat(redis.opsForHash().size(seats(eventId))).isEqualTo(1_000);
    assertThat(redis.opsForValue().get(version(eventId))).isEqualTo("0");
    assertThat(redis.opsForValue().get(available(eventId))).isEqualTo("1000");
    assertThat(redis.<String, String>opsForHash().get(seats(eventId), "A-1-1")).isEqualTo("A");
  }

  @Test
  void aSeatAlreadySoldIsLoadedAsSoldAndNotCountedAsAvailable() {
    UUID eventId = anEventOf(1, 4);
    sell(eventId, "A-1-2");

    seatMap.warmUp(eventId);

    assertThat(redis.<String, String>opsForHash().get(seats(eventId), "A-1-2")).isEqualTo("S");
    assertThat(redis.opsForValue().get(available(eventId))).isEqualTo("3");
  }

  @Test
  void aWarmUpReplacesWhateverWasThere() {
    UUID eventId = anEventOf(1, 2);
    seatMap.warmUp(eventId);
    // A seat of a layout this event no longer has, and a version from an earlier sale.
    redis.opsForHash().put(seats(eventId), "Z-9-9", "H:stale-hold");
    redis.opsForValue().set(version(eventId), "41");

    seatMap.warmUp(eventId);

    assertThat(redis.opsForHash().hasKey(seats(eventId), "Z-9-9")).isFalse();
    assertThat(redis.opsForHash().size(seats(eventId))).isEqualTo(2);
    assertThat(redis.opsForValue().get(version(eventId))).isEqualTo("0");
  }

  @Test
  void theSnapshotReadsTheStatusTheFastPathHolds() {
    UUID eventId = anEventOf(1, 3);
    seatMap.warmUp(eventId);
    redis.opsForHash().put(seats(eventId), "A-1-2", "H:0199c5f0-4444-7000-8000-00000000d004");
    redis.opsForHash().put(seats(eventId), "A-1-3", "S");
    redis.opsForValue().set(version(eventId), "42");

    SeatMapSnapshot snapshot = seatMap.snapshot(eventId);

    assertThat(snapshot.version()).isEqualTo(42L);
    assertThat(snapshot.seats())
        .extracting(SeatMapSnapshot.SeatMapSeat::status)
        .containsExactly(SeatStatus.AVAILABLE, SeatStatus.HELD, SeatStatus.SOLD);
  }

  @Test
  void theSnapshotEndpointAnswersTheShapeTheClientRenders() throws Exception {
    UUID eventId = anEventOf(2, 3);
    events.schedule(eventId);
    events.openForSale(eventId);

    mvc.perform(get("/api/events/{id}/seatmap", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.seats.length()").value(6))
        .andExpect(jsonPath("$.seats[0].id").value("A-1-1"))
        .andExpect(jsonPath("$.seats[0].sectionId").value("A"))
        .andExpect(jsonPath("$.seats[0].x").value(0))
        .andExpect(jsonPath("$.seats[0].y").value(0))
        .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
        .andExpect(jsonPath("$.seats[0].length()").value(5));
  }

  @Test
  void theSnapshotNeverTellsAClientWhoHoldsASeat() throws Exception {
    UUID eventId = anEventOf(1, 2);
    seatMap.warmUp(eventId);
    redis.opsForHash().put(seats(eventId), "A-1-1", "H:0199c5f0-4444-7000-8000-00000000d004");

    mvc.perform(get("/api/events/{id}/seatmap", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.seats[0].status").value("HELD"))
        .andExpect(
            result ->
                assertThat(result.getResponse().getContentAsString())
                    .as("the hold id stays inside the service")
                    .doesNotContain("0199c5f0-4444"));
  }

  @Test
  void anEventThatNeverOpenedHasASnapshotFromPostgresAlone() throws Exception {
    UUID eventId = anEventOf(1, 3);
    sell(eventId, "A-1-3");

    assertThat(redis.hasKey(seats(eventId))).isFalse();
    mvc.perform(get("/api/events/{id}/seatmap", eventId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.seats[0].status").value("AVAILABLE"))
        .andExpect(jsonPath("$.seats[2].status").value("SOLD"));
  }

  @Test
  void theSeatMapOfAnUnknownEventIsNotFound() throws Exception {
    mvc.perform(get("/api/events/{id}/seatmap", UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("NOT_FOUND"));
  }

  @Test
  void aVenueOfTwentyThousandSeatsIsLoadedInOneGo() {
    UUID eventId = anEventOf(200, 100);

    int loaded = seatMap.warmUp(eventId);

    assertThat(loaded).isEqualTo(20_000);
    assertThat(redis.opsForHash().size(seats(eventId))).isEqualTo(20_000);
    assertThat(redis.opsForValue().get(available(eventId))).isEqualTo("20000");
    assertThat(seatMap.snapshot(eventId).seats()).hasSize(20_000);
  }

  private UUID anEventOf(int rows, int seatsPerRow) {
    return events
        .create(
            new CreateEventCommand(
                "Final",
                java.time.Instant.parse("2026-06-01T10:00:00Z"),
                List.of(
                    new SectionLayout("A", "Floor", Money.of(4500L, "EUR"), rows, seatsPerRow))))
        .id();
  }

  private void sell(UUID eventId, String seatId) {
    jdbc.sql(
            """
            INSERT INTO seat_sale (event_id, seat_id, order_id)
            VALUES (:eventId, :seatId, :orderId)
            """)
        .params(Map.of("eventId", eventId, "seatId", seatId, "orderId", UUID.randomUUID()))
        .update();
  }

  private static String seats(UUID eventId) {
    return "seats:{evt:" + eventId + "}";
  }

  private static String version(UUID eventId) {
    return seats(eventId) + ":version";
  }

  private static String available(UUID eventId) {
    return seats(eventId) + ":available";
  }
}
