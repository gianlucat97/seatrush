package com.seatrush.gateway;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * A service behind the gateway, reduced to what the gateway can be asked about it.
 *
 * <p>It answers with the name it was given and with the identity header it was handed, which is how
 * a test can tell routing from authentication: that a request reached queue-service and not
 * inventory is one assertion, and that it arrived carrying the right {@code X-User-Id} is another.
 *
 * <p>A real server on a real port rather than a mocked exchange, because what is being tested is a
 * proxy. Headers that are dropped, a body that streams and a connection that stays open are all
 * things that only exist between two sockets.
 */
final class StubService implements AutoCloseable {

  private final String name;
  private final DisposableServer server;
  private final AtomicReference<Call> lastCall = new AtomicReference<>();

  StubService(String name) {
    this.name = name;
    this.server =
        HttpServer.create()
            .port(0)
            .handle(
                (request, response) -> {
                  lastCall.set(
                      new Call(
                          "/" + request.path(),
                          request.method().name(),
                          request.requestHeaders().get("X-User-Id"),
                          request.requestHeaders().get("X-Admission-Token")));
                  if (request.path().endsWith("stream")) {
                    return streamOf(response);
                  }
                  response.header("Content-Type", "application/json");
                  return response.sendString(Mono.just("{\"service\":\"" + name + "\"}"));
                })
            .bindNow();
  }

  /**
   * Server-sent events, one every so often and for longer than the test will wait.
   *
   * <p>Spread out on purpose: a gateway that buffered the response would hand the client nothing
   * until the last one, so a test that reads the first two before the stream ends is the test that
   * tells the two behaviours apart.
   */
  private static reactor.netty.NettyOutbound streamOf(
      reactor.netty.http.server.HttpServerResponse response) {
    response.header("Content-Type", "text/event-stream");
    return response.sendString(
        Flux.interval(Duration.ZERO, Duration.ofMillis(100))
            .take(50)
            .map(tick -> "event:POSITION\ndata:{\"position\":" + (50 - tick) + "}\n\n"));
  }

  String url() {
    return "http://localhost:" + server.port();
  }

  String name() {
    return name;
  }

  /** The last request this service was asked, or nothing if it was never called. */
  Call lastCall() {
    return lastCall.get();
  }

  void forgetCalls() {
    lastCall.set(null);
  }

  @Override
  public void close() {
    server.disposeNow();
  }

  /** What one request looked like by the time it arrived here, which is after the gateway. */
  record Call(String path, String method, String userId, String admission) {}
}
