package com.seatrush.order.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The store of orders and their items. */
public interface OrderRepository {

  /**
   * Writes an order and its items; the caller owns the transaction.
   *
   * <p>It joins the transaction of the SAGA row and the outbox entry, because the three are one
   * fact: an order that exists without its first command would sit in {@code PENDING} forever
   * (README section 9.5).
   *
   * @throws DuplicateOrderException if this user already used this idempotency key, or if the hold
   *     already has an order
   */
  void insert(Order order);

  Optional<Order> findById(UUID orderId);

  /**
   * The order a user already created with this idempotency key, if any.
   *
   * <p>This is the read half of README section 9.4: a client that retries after a timeout is
   * answered with the order it already has rather than being sent to inventory a second time.
   */
  Optional<Order> findByIdempotencyKey(UUID userId, UUID idempotencyKey);

  /**
   * One page of a user's orders, newest first.
   *
   * <p>Cursor pagination and not an offset: the list grows at the end a user reads from, and an
   * offset would show the same order twice or skip one as soon as a new checkout lands between two
   * pages (README section 12.5).
   *
   * @param before return only orders created strictly before this instant, or null for the first
   *     page
   */
  List<Order> findByUser(UUID userId, Instant before, int limit);
}
