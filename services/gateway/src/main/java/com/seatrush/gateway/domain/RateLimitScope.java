package com.seatrush.gateway.domain;

import java.util.Locale;

/**
 * The three things a buyer can do often enough to be worth limiting (README sections 6.1 and 13).
 *
 * <p>A scope and not one limit for everything, because the three cost different amounts and are
 * abused in different ways. Joining a queue is cheap and happens once, so a buyer who does it fifty
 * times a second is either a broken client or somebody looking for a second place in the line.
 * Holding seats is the expensive one: every attempt is a Lua call on the one Redis that serialises
 * every hold in the system ({@code README} section 9.1), and it is the request a hoarder repeats.
 * Creating an order is the rarest of the three and the only one that starts a SAGA, so its limit is
 * the tightest.
 *
 * <p>The list is deliberately the same three requests {@link PurchaseCommand} guards, plus the
 * queue join. Reads are not here and that is not an omission: a read takes nothing from a sale, and
 * limiting the polling of {@code GET /api/orders/{id}} would be limiting the one thing a buyer past
 * the pivot is told to do.
 *
 * <p>The name is what appears in the Redis key and in the {@code scope} label of the refusal
 * counter, so it is part of what an operator reads on a dashboard rather than an internal detail.
 */
public enum RateLimitScope {
  /** {@code POST /api/events/{id}/queue}: taking a place in the waiting room. */
  QUEUE_JOIN,

  /** {@code POST /api/events/{id}/holds}: taking seats off the market. */
  HOLD,

  /** {@code POST /api/orders}: turning a hold into an order. */
  ORDER;

  private final String label = name().toLowerCase(Locale.ROOT).replace('_', '-');

  /**
   * The stable name of this scope, in the Redis key and in the metric label.
   *
   * <p>Kebab case, so it reads the same as the configuration key under {@code seatrush.ratelimit}
   * that sets its two numbers.
   */
  public String label() {
    return label;
  }
}
