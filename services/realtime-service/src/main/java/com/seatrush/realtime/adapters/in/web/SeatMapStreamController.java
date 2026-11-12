package com.seatrush.realtime.adapters.in.web;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The seat map stream of README section 12.2, which is the half of the live map that moves.
 *
 * <p>One endpoint, and it is the second step of a protocol whose first step belongs to another
 * service. A client loads {@code GET /api/events/{id}/seatmap} from inventory, which answers a
 * whole map at one version, and then opens this stream, which says what changed since. That split
 * is what lets a venue of fifty thousand seats be rendered once and then followed with a few
 * hundred bytes a second.
 *
 * <p>The path carries the {@code /api} prefix the gateway publishes, so a request routed through
 * the gateway and one sent straight to this service hit the same path and no rewrite rule has to be
 * kept in step with a controller. The gateway is also what makes this endpoint authenticated: it
 * validates the JWT of README section 13 before the request arrives, and nothing past it needs to
 * know who the caller is, because every client of one sale is sent exactly the same thing.
 */
@RestController
@RequestMapping("/api/events/{eventId}/seatmap")
@Tag(
    name = "Events and seat map",
    description = "Reading the catalog, an event and the seat map of a sale (README section 12.2)")
public class SeatMapStreamController {

  private final SeatMapStreams streams;

  public SeatMapStreamController(SeatMapStreams streams) {
    this.streams = streams;
  }

  /**
   * Pushes the changes of one seat map until the client leaves or the stream times out.
   *
   * <p>There is nothing to answer here and therefore no error to answer with. A sale that does not
   * exist, one that has not opened and one where nothing is happening are indistinguishable from
   * this service, which holds no catalog, and all three are a connection that is simply quiet. The
   * one thing a client has to handle is a version gap, which every delta carries the means to
   * detect (README section 8.4).
   */
  // What the published document would otherwise say here is the shape of SseEmitter, which is a
  // Spring class with a timeout field and nothing a client ever receives. The body of this
  // response is an event stream, whose grammar belongs to the SSE specification rather than to
  // JSON Schema, so it is described as the text it is and the event names are named in the text.
  @ApiResponse(
      responseCode = "200",
      description =
          "An open event stream. One DELTA event per batch, with a JSON body of"
              + " {fromVersion, toVersion, changes}, and a comment line as a keep-alive. A gap"
              + " between toVersion and the next fromVersion means a client has to reload the"
              + " snapshot from inventory (README sections 8.4 and 12.2)",
      content =
          @Content(
              mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
              schema = @Schema(type = "string")))
  @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter stream(@PathVariable UUID eventId) {
    return streams.open(eventId);
  }
}
