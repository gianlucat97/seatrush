package com.seatrush.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.money.Money;
import com.seatrush.inventory.application.CreateEventCommand;
import com.seatrush.inventory.application.EventCatalogService;
import com.seatrush.inventory.application.HoldService;
import com.seatrush.inventory.application.PlaceHoldCommand;
import com.seatrush.inventory.application.SeatEventRelay;
import com.seatrush.inventory.domain.HoldResult;
import com.seatrush.inventory.domain.SeatEventRecord;
import com.seatrush.inventory.domain.SeatEventStream;
import com.seatrush.inventory.domain.SectionLayout;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SeatsHeld;
import com.seatrush.messaging.messages.SeatsReleased;
import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The Redis outbox end to end: a hold changes seats in Redis, and the change comes out of Kafka.
 *
 * <p>Three containers, because this is the one place where the whole chain of README section 9.5 is
 * visible: the Lua script appended the event inside the same atomic call that changed the seats,
 * the relay reads it through a consumer group, and only then acknowledges it.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // Claimed as soon as it is pending, so the test does not wait half a minute to prove that
      // an abandoned entry comes back.
      "seatrush.stream-relay.claim-idle-after=1ms",
      // The scheduled relay is pushed out of the way: this test drives it by hand, and a pass
      // firing in the background would relay the entries the test is about to assert on.
      "seatrush.stream-relay.poll-interval=1h",
      "seatrush.hold.sweeper-interval=1h"
    })
@EnabledIfDockerAvailable
class SeatEventRelayIT {

  private static final String DATABASE = "inventory_relay_it";
  private static final Instant ON_SALE = Instant.parse("2026-06-01T10:00:00Z");

  @Autowired private EventCatalogService events;
  @Autowired private HoldService holds;
  @Autowired private SeatEventRelay relay;
  @Autowired private SeatEventStream stream;
  @Autowired private MessageCodec codec;
  @Autowired private MutableClock clock;
  @Autowired private JdbcClient jdbc;

  private UUID eventId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    RedisSupport.registerProperties(registry);
    KafkaSupport.registerProperties(registry);
  }

  @BeforeEach
  void anOpenSale() {
    clock.setTo(ON_SALE);
    eventId = anEventOnSale();
  }

  @AfterEach
  void emptyTheStores() {
    jdbc.sql("TRUNCATE seat_sale, seat, section, event CASCADE").update();
    RedisSupport.flushAll();
  }

  @Test
  void aHoldComesOutOfKafkaAsASeatsHeldMessage() {
    HoldResult.Held held = hold("A-1-1", "A-1-2");

    assertThat(relay.relayOnce()).isEqualTo(1);

    ConsumerRecord<String, String> record = oneRecord();
    assertThat(record.key())
        .as("keyed by event, so one seat map is one partition and stays ordered")
        .isEqualTo(eventId.toString());

    MessageEnvelope<Message> envelope = codec.decode(record.value());
    assertThat(envelope.type()).isEqualTo("SeatsHeld");
    assertThat(envelope.schemaVersion()).isEqualTo(1);
    assertThat(envelope.correlationId()).isEqualTo(eventId);
    assertThat(envelope.occurredAt())
        .as("the time of the change, not the time of the relay")
        .isEqualTo(ON_SALE);
    assertThat(envelope.payload())
        .isEqualTo(
            new SeatsHeld(eventId, List.of("A-1-1", "A-1-2"), held.version(), held.hold().id()));
  }

  @Test
  void aReleaseFollowsItsHoldInOrder() {
    HoldResult.Held held = hold("A-1-1");
    clock.advanceBy(Duration.ofSeconds(30));
    holds.release(held.hold().id(), held.hold().userId());

    assertThat(relay.relayOnce()).isEqualTo(2);

    List<MessageEnvelope<Message>> envelopes = envelopesOf(records(2));
    assertThat(envelopes)
        .extracting(MessageEnvelope::type)
        .containsExactly("SeatsHeld", "SeatsReleased");
    assertThat(envelopes.getLast().payload())
        .isEqualTo(new SeatsReleased(eventId, List.of("A-1-1"), 2L, held.hold().id()));
    assertThat(envelopes.getLast().occurredAt()).isEqualTo(ON_SALE.plusSeconds(30));
  }

  @Test
  void whatWasRelayedIsAcknowledgedAndNotRelayedAgain() {
    hold("A-1-1");

    relay.relayOnce();

    assertThat(stream.pendingCount(eventId)).isZero();
    assertThat(relay.relayOnce()).as("a second pass has nothing left to do").isZero();
    assertThat(records(1)).hasSize(1);
  }

  @Test
  void anEntryTakenByARelayThatNeverCameBackIsPublishedByTheNextOne() {
    hold("A-1-1");
    // A relay that read the entry and died before publishing it: the entry stays pending and
    // nobody else would be offered it without the claim.
    List<SeatEventRecord> taken = stream.read(eventId, 10);
    assertThat(taken).hasSize(1);
    assertThat(stream.pendingCount(eventId)).isEqualTo(1);

    assertThat(relay.relayOnce()).as("the next pass claims what was abandoned").isEqualTo(1);

    assertThat(stream.pendingCount(eventId)).isZero();
    assertThat(oneRecord().value()).contains("SeatsHeld");
  }

  @Test
  void everyChangeOfACrowdedSaleReachesKafkaExactlyOnceInVersionOrder() {
    int seats = 10;
    for (int i = 1; i <= seats; i++) {
      hold("A-1-" + i);
    }

    assertThat(relay.relayOnce()).isEqualTo(seats);

    List<MessageEnvelope<Message>> envelopes = envelopesOf(records(seats));
    assertThat(envelopes)
        .extracting(envelope -> ((SeatsHeld) envelope.payload()).version())
        .containsExactlyElementsOf(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L));
    assertThat(envelopes).extracting(MessageEnvelope::messageId).doesNotHaveDuplicates();
  }

  private HoldResult.Held hold(String... seatIds) {
    return (HoldResult.Held)
        holds.place(
            new PlaceHoldCommand(
                eventId, UUID.randomUUID(), List.of(seatIds), UUID.randomUUID().toString()));
  }

  private UUID anEventOnSale() {
    UUID id =
        events
            .create(
                new CreateEventCommand(
                    "Final",
                    ON_SALE,
                    List.of(new SectionLayout("A", "Floor", Money.of(4500L, "EUR"), 1, 10))))
            .id();
    events.schedule(id);
    events.openForSale(id);
    return id;
  }

  private ConsumerRecord<String, String> oneRecord() {
    return records(1).getFirst();
  }

  /**
   * Reads the topic from the beginning and keeps the records of this test's event.
   *
   * <p>The topic is shared by every test of the class, as it is by every event in production. The
   * key is what separates them, which is the same thing that keeps one seat map in one partition.
   */
  private List<ConsumerRecord<String, String>> records(int expected) {
    List<ConsumerRecord<String, String>> received = new ArrayList<>();
    try (KafkaConsumer<String, String> consumer = consumer()) {
      consumer.subscribe(List.of(Topics.INVENTORY_SEAT_EVENTS));
      long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
      while (received.size() < expected && System.nanoTime() < deadline) {
        ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(500));
        batch.forEach(
            record -> {
              if (record.key().equals(eventId.toString())) {
                received.add(record);
              }
            });
      }
    }
    assertThat(received).as("seat events of event %s", eventId).hasSize(expected);
    return received;
  }

  private List<MessageEnvelope<Message>> envelopesOf(List<ConsumerRecord<String, String>> records) {
    return records.stream().map(record -> codec.decode(record.value())).toList();
  }

  private static KafkaConsumer<String, String> consumer() {
    return new KafkaConsumer<>(
        Map.of(
            ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
            KafkaSupport.bootstrapServers(),
            ConsumerConfig.GROUP_ID_CONFIG,
            "relay-it-" + UUID.randomUUID(),
            ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,
            "earliest",
            ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName(),
            ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,
            StringDeserializer.class.getName()));
  }

  /** The clock the service reads, so the time of a change is the time this test chose. */
  @TestConfiguration
  static class MovableTime {

    @Bean
    Clock clock() {
      return MutableClock.at(ON_SALE);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }
}
