package com.seatrush.queue.adapters.in.web;

import com.seatrush.queue.application.QueueService;
import com.seatrush.queue.application.SaleCalendar;
import com.seatrush.queue.application.WaitingPlace;
import com.seatrush.queue.domain.NotWaitingException;
import java.time.Instant;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The waiting room endpoints of README section 12.3.
 *
 * <p>{@code X-User-Id} is the identity of the caller. The gateway strips whatever a client sent and
 * sets it from the validated JWT (README section 13), so inside the cluster it is trusted; until
 * the gateway exists, a caller sets it itself, which is also what makes the local stack usable.
 *
 * <p>Paths carry the {@code /api} prefix the gateway publishes, so a request routed through the
 * gateway and one sent straight to this service hit the same path and no rewrite rule has to be
 * kept in step with the controllers.
 *
 * <p>Three of the four endpoints answer a question and the fourth keeps answering it: {@code GET
 * .../queue/stream} pushes the same place {@code GET .../queue/me} returns, every time it changes.
 * A client uses one or the other, and {@code me} is not only the fallback for a lost stream: it is
 * also where an admitted buyer gets a fresh token, since the token is minted from the slot on every
 * read (ADR-0016) and the one a client was pushed is the same token.
 */
@RestController
@RequestMapping("/api/events/{eventId}/queue")
public class QueueController {

  private final QueueService queue;
  private final SaleCalendar calendar;
  private final QueueStreams streams;

  public QueueController(QueueService queue, SaleCalendar calendar, QueueStreams streams) {
    this.queue = queue;
    this.calendar = calendar;
    this.streams = streams;
  }

  /**
   * Joins the waiting room, or returns the place the user already has.
   *
   * <p>200 and not 201, even though the first call does create something. The endpoint is
   * idempotent by design (README section 6.2), so the second call would have to answer 200 with the
   * same body, and a client that had to tell a first join from a repeat in order to read the
   * response would be a client that handles a lost connection badly.
   *
   * <p>There is no {@code Idempotency-Key} for the same reason: the user and the event are the key.
   * A hold needs one because two holds of different seats are two different requests, while a
   * second join of the same sale by the same user cannot be anything but the same request.
   */
  @PostMapping
  public QueuePlaceResponse join(
      @PathVariable UUID eventId, @RequestHeader("X-User-Id") UUID userId) {
    return QueuePlaceResponse.of(queue.join(eventId, userId), onSaleAtOf(eventId));
  }

  /** Where the caller stands, for a client that has no stream or just lost one. */
  @GetMapping("/me")
  public QueuePlaceResponse me(
      @PathVariable UUID eventId, @RequestHeader("X-User-Id") UUID userId) {
    WaitingPlace waiting =
        queue.place(eventId, userId).orElseThrow(() -> new NotWaitingException(eventId, userId));
    return QueuePlaceResponse.of(waiting, onSaleAtOf(eventId));
  }

  /**
   * Pushes where the caller stands, until there is nothing left to push.
   *
   * <p>The place is read here rather than inside the stream, because this is where an answer can
   * still be an error: somebody who is not waiting is told so with the same 404 {@code me} gives,
   * instead of being handed a stream that would never say anything. After that the connection is
   * the answer, and it ends itself when the user is admitted or the sale sells out.
   *
   * <p>Nothing about the user's place changes by connecting. A client may open the stream, lose it
   * and poll instead, and a client that does both is told the same thing twice rather than counted
   * twice: a listener is a socket this pod holds, not a seat in the queue.
   */
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID eventId, @RequestHeader("X-User-Id") UUID userId) {
    WaitingPlace waiting =
        queue.place(eventId, userId).orElseThrow(() -> new NotWaitingException(eventId, userId));
    return streams.open(waiting);
  }

  /**
   * Leaves the waiting room.
   *
   * <p>204 whether or not the user was waiting. A client leaving a queue is saying what it wants
   * the state to be, and that state is reached either way; answering 404 to the second of two
   * clicks would turn a successful outcome into an error a client has to special-case.
   */
  @DeleteMapping
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void leave(@PathVariable UUID eventId, @RequestHeader("X-User-Id") UUID userId) {
    queue.leave(eventId, userId);
  }

  /**
   * The sale time of an event, or nothing if this service has not seen it.
   *
   * <p>Absent rather than an error: a user queueing for an event that has since left the catalog
   * still deserves to be told where they stand, and the countdown is the one part of the answer
   * that can be missing without making the rest useless.
   */
  private Instant onSaleAtOf(UUID eventId) {
    return calendar.find(eventId).map(event -> event.onSaleAt()).orElse(null);
  }
}
