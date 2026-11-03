package com.seatrush.realtime.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How a pushed stream is kept alive and how long it may live (README section 22).
 *
 * <p>{@code heartbeatInterval} is what a proxy and a mobile network need in order to be told that a
 * quiet connection is not a dead one. A seat map is quiet far more often than a waiting room is: a
 * sale that has not opened yet, a venue between bursts and a spectator watching a finished event
 * all produce nothing for minutes, and every one of those connections is worth keeping.
 *
 * <p>{@code timeout} is the other half of the same problem, read from the server's side. A client
 * that goes away without closing anything leaves a connection that looks open until something is
 * written to it, and a spectator tab left open overnight is exactly that. The stream ends on its
 * own, the client reconnects and reloads a snapshot, and what a pod holds stays bounded.
 *
 * <p>The prefix is {@code seatrush.sse} and not {@code seatrush.realtime} because README section 22
 * gives the same two knobs to the waiting room stream of queue-service: one answer to the question
 * of how this system treats an idle stream, rather than one answer per service.
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
