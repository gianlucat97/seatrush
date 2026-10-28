package com.seatrush.queue.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How closely the waiting room follows the catalog, and how it drains a lobby (README section 22).
 *
 * <p>{@code catalogRefreshInterval} is the whole latency of a sale opening: inventory moves an
 * event to {@code ON_SALE} and this service learns of it within one interval, which is when the
 * lobby is shuffled into the queue. A second is short enough that nobody notices and long enough
 * that the request costs nothing, since one call serves every join of that second.
 *
 * <p>{@code lobbyOpenChunkSize} is not a limit on how many users are drained per pass. The whole
 * lobby is shuffled and queued in one pass, because a shuffle of part of it would not be a shuffle;
 * the chunk is only how many of them travel in one Lua call, so that a 200,000 user lobby does not
 * become a single script invocation that blocks Redis for everybody else. Each chunk is atomic and
 * the order across chunks is the shuffled one, so splitting changes nothing a user can observe.
 *
 * <p>{@code positionUpdateInterval} is how often the stream of README section 12.3 looks at every
 * sale somebody is listening to. It is a few seconds rather than one because a position is a number
 * a human reads: pushing it every second would be three times the traffic for a figure nobody can
 * follow that fast, and a client that wants it sooner can ask for it.
 *
 * @param catalogRefreshInterval how often inventory's event list is read
 * @param lobbyOpenInterval how often an open sale is checked for a lobby still to drain
 * @param lobbyOpenChunkSize how many users are appended to the queue per Lua call
 * @param positionUpdateInterval how often positions are pushed to the clients listening
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.queue")
public record QueueProperties(
    @NotNull @DefaultValue("1s") Duration catalogRefreshInterval,
    @NotNull @DefaultValue("1s") Duration lobbyOpenInterval,
    @Min(1) @Max(10_000) @DefaultValue("1000") int lobbyOpenChunkSize,
    @NotNull @DefaultValue("3s") Duration positionUpdateInterval) {

  public QueueProperties {
    requirePositive(catalogRefreshInterval, "catalog-refresh-interval");
    requirePositive(lobbyOpenInterval, "lobby-open-interval");
    requirePositive(positionUpdateInterval, "position-update-interval");
  }

  private static void requirePositive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.queue.%s must be positive, was %s".formatted(name, value));
    }
  }
}
