package com.seatrush.realtime.domain;

import java.util.UUID;

/**
 * The clients this pod is pushing a seat map to.
 *
 * <p>A port, so that the batching of deltas and the holding of connections stay apart. One is
 * arithmetic over version numbers and is worth testing without a socket in sight; the other is a
 * registry of open streams that only exists inside a web container.
 *
 * <p>The implementation is an inbound adapter, which is unusual and is the honest place for it: a
 * listening client is something that arrived from outside, and a delta is handed back to it the way
 * a response is handed back to a request.
 */
public interface SeatMapAudience {

  /**
   * Pushes one delta to every client watching that sale here.
   *
   * <p>Never throws because a client has gone away. A dropped connection is the ordinary end of an
   * SSE stream during an on-sale, and a batch must not fail over one.
   */
  void send(SeatMapDelta delta);

  /**
   * How many clients are watching one sale on this pod.
   *
   * <p>Asked by the batcher, which keeps nothing for a sale that nobody is watching and nothing is
   * happening to: a pod that served a sold out sale an hour ago should not still be carrying it.
   */
  int watching(UUID eventId);
}
