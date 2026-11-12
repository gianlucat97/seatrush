package com.seatrush.queue;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.Topics;
import com.seatrush.messaging.messages.FailureReason;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.OrderConfirmed;
import com.seatrush.messaging.messages.OrderFailed;
import com.seatrush.queue.adapters.out.redis.QueueKeys;
import com.seatrush.queue.application.AdmissionJob;
import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.domain.AdmissionBatch;
import com.seatrush.queue.domain.EventCatalog;
import com.seatrush.queue.domain.SaleEvent;
import com.seatrush.queue.domain.SaleStatus;
import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.containers.RedisSupport;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The end of a checkout as the waiting room hears it: a message on {@code order.events} and a slot
 * that goes back to the next buyer in line (README sections 6.2 and 10.1).
 *
 * <p>Three things can only be shown with a real broker behind a real Redis. That a published event
 * reaches the listener of this service at all, that both outcomes of a checkout free the room, and
 * that what is freed is immediately usable: the test fills a purchase area of one, ends the order
 * inside it, and watches the door let the next person through.
 */
@SpringBootTest
@TestPropertySource(
    properties = {
      // The schedulers are driven by hand: a pass firing between an act and an assertion would
      // make the result a race. The listener is the one thing here that runs on its own.
      "seatrush.queue.catalog-refresh-interval=1h",
      "seatrush.queue.lobby-open-interval=1h",
      "seatrush.queue.position-update-interval=1h",
      "seatrush.admission.interval=1h",
      "seatrush.admission.token-ttl=10m",
      // A purchase area of one, so that the next admission is proof the room was given back.
      "seatrush.admission.max-active-users=1"
    })
@EnabledIfDockerAvailable
class QueueSlotReleaseIT {

  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");
  private static final Duration PATIENCE = Duration.ofSeconds(30);

  @Autowired private QueueService queue;
  @Autowired private AdmissionJob door;
  @Autowired private SaleCalendar calendar;
  @Autowired private ReleaseStubCatalog catalog;
  @Autowired private MessageFactory messages;
  @Autowired private MessageCodec codec;
  @Autowired private KafkaTemplate<String, String> kafka;
  @Autowired private MutableClock clock;
  @Autowired private StringRedisTemplate redis;

  private UUID eventId;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    RedisSupport.registerProperties(registry);
    KafkaSupport.registerProperties(registry);
    // Explicitly, because auto-creation is off on every broker that matters (README section 10.1).
    KafkaSupport.createTopics(Topics.ORDER_EVENTS);
  }

  @BeforeEach
  void aLiveSale() {
    clock.setTo(ON_SALE_AT);
    eventId = UUID.randomUUID();
    catalog.publish(new SaleEvent(eventId, "A sale", ON_SALE_AT, SaleStatus.ON_SALE));
    calendar.refresh();
  }

  @AfterEach
  void emptyTheKeyspace() {
    RedisSupport.flushAll();
  }

  @Test
  void givesTheSlotBackWhenAnOrderIsConfirmed() {
    UUID ada = admitOneBuyer();

    publish(new OrderConfirmed(UUID.randomUUID(), ada, eventId));

    waitUntil(() -> !holdsASlot(ada));
  }

  @Test
  void givesTheSlotBackWhenAnOrderFails() {
    UUID ada = admitOneBuyer();

    publish(new OrderFailed(UUID.randomUUID(), ada, eventId, FailureReason.PAYMENT_DECLINED));

    waitUntil(() -> !holdsASlot(ada));
  }

  /**
   * The point of freeing it early: the minutes until that admission would have expired on its own
   * are somebody else's turn.
   */
  @Test
  void letsTheNextBuyerThroughTheDoorTheMomentTheRoomIsFree() {
    UUID ada = admitOneBuyer();
    UUID grace = UUID.randomUUID();
    queue.join(eventId, grace);
    assertThat(door.admitFrom(calendar.event(eventId)).admitted())
        .as("a purchase area of one is full while Ada is in it")
        .isEmpty();

    publish(new OrderConfirmed(UUID.randomUUID(), ada, eventId));
    waitUntil(() -> !holdsASlot(ada));

    AdmissionBatch batch = door.admitFrom(calendar.event(eventId));
    assertThat(batch.admitted()).containsExactly(grace);
  }

  /** The buyer joins, the door lets them in, and the purchase area of one is now full. */
  private UUID admitOneBuyer() {
    UUID userId = UUID.randomUUID();
    queue.join(eventId, userId);
    assertThat(door.admitFrom(calendar.event(eventId)).admitted()).containsExactly(userId);
    return userId;
  }

  private void publish(Message payload) {
    var envelope = messages.wrap(payload);
    kafka.send(
        Topics.ORDER_EVENTS, envelope.partitionKey().toString(), codec.encodeToString(envelope));
  }

  private boolean holdsASlot(UUID userId) {
    return redis.opsForZSet().score(QueueKeys.active(eventId), userId.toString()) != null;
  }

  /** Waits for the listener, which is the one thing in this test that runs on its own schedule. */
  private static void waitUntil(BooleanSupplier condition) {
    long deadline = System.nanoTime() + PATIENCE.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(50);
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("interrupted while waiting for the listener", interrupted);
      }
    }
    throw new AssertionError("the listener did not act within " + PATIENCE);
  }

  /** The catalog this test writes the answers of, and the clock it moves. */
  @TestConfiguration
  static class ACatalogAndAClock {

    @Bean
    @Primary
    ReleaseStubCatalog catalog() {
      return new ReleaseStubCatalog();
    }

    @Bean
    Clock clock() {
      return MutableClock.at(ON_SALE_AT);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }

  /** An {@link EventCatalog} a test publishes into, standing in for inventory. */
  static class ReleaseStubCatalog implements EventCatalog {

    private volatile List<SaleEvent> events = List.of();

    @Override
    public List<SaleEvent> events() {
      return events;
    }

    void publish(SaleEvent... published) {
      this.events = List.of(published);
    }
  }
}
