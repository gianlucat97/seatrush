package com.seatrush.order;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * An inventory service that is only the freeze endpoint, for the integration tests of the checkout.
 *
 * <p>A real socket rather than a mocked client, because what these tests are about is the checkout
 * end to end: the request has to be serialised, sent, parsed and answered, and a stubbed client
 * would skip exactly the part where the two services have to agree on a JSON shape.
 *
 * <p>The JDK server and not a library: it is one endpoint, it has to start in milliseconds, and a
 * test dependency that opens its own sockets is a dependency that can fail on somebody's machine
 * for reasons that have nothing to do with the checkout.
 */
public final class StubInventoryServer {

  private final HttpServer server;
  private final List<String> requestBodies = new ArrayList<>();

  private volatile int status = 200;
  private volatile String body = "";

  private StubInventoryServer(HttpServer server) {
    this.server = server;
  }

  /** Starts a server on a free port the operating system picks. */
  public static StubInventoryServer start() {
    try {
      HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      StubInventoryServer stub = new StubInventoryServer(server);
      server.createContext("/internal/holds", stub::handle);
      server.start();
      return stub;
    } catch (IOException e) {
      throw new IllegalStateException("could not start the inventory stub", e);
    }
  }

  public String baseUrl() {
    return "http://127.0.0.1:" + server.getAddress().getPort();
  }

  public void stop() {
    server.stop(0);
  }

  /** Answers every freeze with these seats, all priced the same, and that total. */
  public void freezes(
      UUID holdId, UUID eventId, UUID userId, long centsPerSeat, String... seatIds) {
    StringBuilder seats = new StringBuilder();
    for (int i = 0; i < seatIds.length; i++) {
      seats
          .append(i == 0 ? "" : ",")
          .append(
              """
              {"seatId":"%s","sectionId":"A","price":{"amountCents":%d,"currency":"EUR"}}"""
                  .formatted(seatIds[i], centsPerSeat));
    }
    this.status = 200;
    this.body =
        """
        {"holdId":"%s","eventId":"%s","userId":"%s","orderId":"%s","status":"CHECKOUT",
         "seats":[%s],"total":{"amountCents":%d,"currency":"EUR"}}"""
            .formatted(
                holdId, eventId, userId, UUID.randomUUID(), seats, centsPerSeat * seatIds.length);
  }

  /** Refuses every freeze with a Problem Details document carrying this code. */
  public void refuses(int httpStatus, String code, String detail) {
    this.status = httpStatus;
    this.body =
        """
        {"type":"urn:seatrush:error:x","title":"t","status":%d,"detail":"%s","code":"%s"}"""
            .formatted(httpStatus, detail, code);
  }

  /** The bodies this server was sent, in order, so a test can assert it was called once. */
  public List<String> requestBodies() {
    return List.copyOf(requestBodies);
  }

  public int callCount() {
    return requestBodies.size();
  }

  public void forgetCalls() {
    requestBodies.clear();
  }

  private void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
    try (exchange) {
      byte[] request = exchange.getRequestBody().readAllBytes();
      synchronized (requestBodies) {
        requestBodies.add(new String(request, StandardCharsets.UTF_8));
      }

      byte[] answer = body.getBytes(StandardCharsets.UTF_8);
      exchange
          .getResponseHeaders()
          .add("Content-Type", status == 200 ? "application/json" : "application/problem+json");
      exchange.sendResponseHeaders(status, answer.length);
      try (OutputStream out = exchange.getResponseBody()) {
        out.write(answer);
      }
    }
  }
}
