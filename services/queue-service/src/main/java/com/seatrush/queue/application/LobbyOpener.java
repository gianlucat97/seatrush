package com.seatrush.queue.application;

import com.seatrush.queue.config.QueueProperties;
import com.seatrush.queue.domain.QueueRegistry;
import com.seatrush.queue.domain.SaleEvent;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Shuffles the lobby of a sale that has opened into its queue (README section 6.2).
 *
 * <p>This is the fairness of the whole waiting room, and it is one idea: everybody who was waiting
 * before the doors opened is put in the queue in a random order. Arriving at midnight and arriving
 * a minute before it are then worth exactly the same, which removes the reason to hammer refresh at
 * the announced second, and with it the burst that would otherwise land on the join endpoint at a
 * time nobody can smooth out.
 *
 * <p>Shuffled here rather than in Lua: Redis makes {@code math.random} deterministic so that a
 * replica replaying a script reaches the same state, and a deterministic shuffle is a fixed
 * permutation that merely looks fair. {@link SecureRandom} rather than {@code Random}, because a
 * predictable order is a predictable advantage and this is the one ordering somebody would pay to
 * know in advance.
 *
 * <p>It runs on every pod at once with no leader and no lock, like the hold sweeper. What makes
 * that safe is the script: removing a user from the lobby set is the claim, so two pods shuffling
 * the same lobby differently still give each user exactly one sequence number, and the second pod
 * simply finds nothing left to claim. The two shuffles interleave, which is another shuffle.
 *
 * <p>A pass drains the whole lobby, because a shuffle of part of it would not be a shuffle. Only
 * the trip to Redis is chunked, so that a lobby of two hundred thousand does not become one script
 * invocation holding Redis for everybody else.
 */
@Component
public class LobbyOpener {

  private static final Logger log = LoggerFactory.getLogger(LobbyOpener.class);

  private final SaleCalendar calendar;
  private final QueueRegistry registry;
  private final QueueProperties properties;
  private final SecureRandom shuffle = new SecureRandom();

  public LobbyOpener(SaleCalendar calendar, QueueRegistry registry, QueueProperties properties) {
    this.calendar = calendar;
    this.registry = registry;
    this.properties = properties;
  }

  /**
   * One pass over every sale that is live.
   *
   * <p>A fixed delay and not a fixed rate: a pass that took longer than the interval, which a very
   * large lobby can, must not have the next one queued behind it.
   *
   * <p>It keeps running after the lobby is empty, and that is deliberate rather than wasteful: a
   * user whose join landed in the lobby a moment before inventory opened the sale would otherwise
   * wait there for ever. An empty lobby costs one {@code SMEMBERS} of a missing key per sale.
   */
  @Scheduled(
      fixedDelayString = "${seatrush.queue.lobby-open-interval:1s}",
      initialDelayString = "${seatrush.queue.lobby-open-interval:1s}")
  public void openLobbies() {
    for (SaleEvent event : calendar.selling()) {
      try {
        open(event);
      } catch (RuntimeException failure) {
        // One sale that could not be drained must not stop the others, and the next pass will
        // try again: nothing here has been half done, because each chunk is atomic.
        log.error("the lobby of event {} could not be opened", event.id(), failure);
      }
    }
  }

  /**
   * Drains one lobby.
   *
   * @return how many users this pass moved into the queue
   */
  public int open(SaleEvent event) {
    List<UUID> waiting = new ArrayList<>(registry.lobby(event.id()));
    if (waiting.isEmpty()) {
      return 0;
    }

    Collections.shuffle(waiting, shuffle);

    int queued = 0;
    int chunkSize = properties.lobbyOpenChunkSize();
    for (int from = 0; from < waiting.size(); from += chunkSize) {
      List<UUID> chunk = waiting.subList(from, Math.min(from + chunkSize, waiting.size()));
      queued += registry.openLobby(event.id(), chunk);
    }

    if (queued > 0) {
      log.info(
          "the lobby of event {} ({}) was shuffled into the queue: {} of {} users were queued here",
          event.id(),
          event.name(),
          queued,
          waiting.size());
    }
    return queued;
  }
}
