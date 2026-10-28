package com.seatrush.queue.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How a pushed stream is kept alive and how long it may live (README section 22).
 *
 * <p>{@code heartbeatInterval} is what a proxy and a mobile network need to be told that a quiet
 * connection is not a dead one. It is sent as a comment rather than as a position, because a
 * position that has not changed is not news and a sale of a hundred thousand waiting clients would
 * be pushing a hundred thousand copies of the same number every few seconds.
 *
 * <p>{@code timeout} is the other half of the same problem, read from the server's side. A client
 * that goes away without closing anything leaves a connection that looks open until something is
 * written to it, and a waiting room is precisely the place where thousands of those accumulate. The
 * stream ends on its own and the client reconnects, which costs one request and bounds what a pod
 * holds. It is far longer than an ordinary wait, so nobody is cut off mid-queue.
 *
 * <p>The prefix is {@code seatrush.sse} and not {@code seatrush.queue} because README section 22
 * gives the same knob to the seat map stream of realtime-service: one answer to the question of how
 * often this system keeps an idle stream alive.
 *
 * @param heartbeatInterval how long a listener may hear nothing before a keep-alive is sent
 * @param timeout how long one stream may stay open before the client is asked to reconnect
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.sse")
public record SseProperties(
    @NotNull @DefaultValue("15s") Duration heartbeatInterval,
    @NotNull @DefaultValue("30m") Duration timeout) {

  public SseProperties {
    requirePositive(heartbeatInterval, "heartbeat-interval");
    requirePositive(timeout, "timeout");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.sse.%s must be positive, was %s".formatted(name, value));
    }
  }
}
