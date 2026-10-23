package com.seatrush.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import com.seatrush.common.money.Money;
import com.seatrush.order.domain.Order;
import com.seatrush.order.domain.OrderItem;
import com.seatrush.order.domain.OrderNotFoundException;
import com.seatrush.order.domain.OrderNotOwnedException;
import com.seatrush.order.domain.OrderStatus;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderQueryServiceTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Money SEAT = Money.of(4500L, "EUR");

  private final MutableClock clock = MutableClock.at(NOW);
  private final IdGenerator ids = new UuidV7Generator(clock);
  private final InMemoryOrders orders = new InMemoryOrders();
  private final OrderQueryService queries = new OrderQueryService(orders);

  @Test
  void answersWithAnOrderToItsOwner() {
    Order order = anOrder(USER_ID);

    assertThat(queries.get(order.id(), USER_ID)).isEqualTo(order);
  }

  @Test
  void refusesAnOrderOfAnotherUser() {
    Order order = anOrder(USER_ID);

    assertThatThrownBy(() -> queries.get(order.id(), UUID.randomUUID()))
        .isInstanceOf(OrderNotOwnedException.class);
  }

  @Test
  void reportsAnOrderThatDoesNotExist() {
    assertThatThrownBy(() -> queries.get(UUID.randomUUID(), USER_ID))
        .isInstanceOf(OrderNotFoundException.class);
  }

  @Test
  void listsOnlyTheCallersOwnOrdersNewestFirst() {
    Order oldest = anOrder(USER_ID);
    clock.advanceBy(Duration.ofMinutes(1));
    anOrder(UUID.randomUUID());
    clock.advanceBy(Duration.ofMinutes(1));
    Order newest = anOrder(USER_ID);

    assertThat(queries.list(USER_ID, null, null))
        .extracting(Order::id)
        .containsExactly(newest.id(), oldest.id());
  }

  @Test
  void readsTheNextPageFromTheCursor() {
    Order first = anOrder(USER_ID);
    clock.advanceBy(Duration.ofMinutes(1));
    Order second = anOrder(USER_ID);

    List<Order> page = queries.list(USER_ID, second.createdAt(), null);

    assertThat(page).extracting(Order::id).containsExactly(first.id());
  }

  @Test
  void defaultsAndCapsThePageSize() {
    assertThat(OrderQueryService.pageSize(null)).isEqualTo(OrderQueryService.DEFAULT_PAGE_SIZE);
    assertThat(OrderQueryService.pageSize(5)).isEqualTo(5);
    assertThat(OrderQueryService.pageSize(1_000)).isEqualTo(OrderQueryService.MAX_PAGE_SIZE);
  }

  @Test
  void refusesAPageOfNothing() {
    assertThatThrownBy(() -> queries.list(USER_ID, null, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("limit must be positive");
    assertThatThrownBy(() -> queries.list(USER_ID, null, -3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Order anOrder(UUID userId) {
    Order order =
        new Order(
            ids.newId(),
            userId,
            EVENT_ID,
            UUID.randomUUID(),
            OrderStatus.PENDING,
            null,
            List.of(new OrderItem("A-1-1", SEAT)),
            SEAT,
            UUID.randomUUID(),
            "hash",
            clock.instant(),
            clock.instant());
    orders.insert(order);
    return order;
  }
}
