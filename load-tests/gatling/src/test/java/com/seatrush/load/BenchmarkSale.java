package com.seatrush.load;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The sale a run is run against, created through the public API before the injection starts.
 *
 * <p>Created by the run and not seeded beforehand, for the reason {@code scripts/seed.sh} gives
 * about itself: the only way an event exists in this system is the admin endpoint of README section
 * 12.2, because that is what generates the layout and what the Redis warm-up hangs off. A benchmark
 * that inserted rows would be measuring a sale that never opened.
 *
 * <p>Both sales are scheduled out of the sale opener's reach, which is the one decision here worth
 * reading twice. {@code DRAFT} to {@code SCHEDULED} is what opens the lobby (ADR-0015), so a sale
 * whose {@code onSaleAt} is hours out still fills; what it does not do is open itself. The run
 * opens the one being bought from with a request of its own, for the reason {@link
 * OnSaleSimulation} gives about its opener: a benchmark wants the instant it starts measuring from
 * to be a request it made.
 *
 * <p>It used to be scheduled {@code lobbySeconds} out, which is the same instant the opener fires
 * at, so the sale opener of README section 6.3 and the opener of this simulation raced for it and
 * the loser was answered {@code 400}. One request nobody designed for is enough to fail the single
 * assertion every run of this simulation is gated on, which made a clean run a coin toss and is why
 * the clock is now kept out of it entirely.
 *
 * <p>The event id is written to a file as well as returned, because the run is only half of {@code
 * make load-small}: the invariant checker of README section 17.1 is the other half, and it has to
 * be told which event to read.
 *
 * <p>Two events, and the second one is the only part of this that is not obvious. The waiting room
 * stream of README section 12.3 ends itself the moment its user is admitted, which is correct and
 * which makes it impossible to hold one open on a sale that is admitting: a client doing that is
 * not a connection under load, it is a connection that lasted until its turn came. So the clients
 * whose job is to be a concurrent queue connection (README section 15) wait in the lobby of a sale
 * that does not open during the run. It is the same service, the same fan-out job and the same
 * sockets; what it is not is the same event, and that is the price of measuring it at all.
 */
record BenchmarkSale(UUID eventId, Instant onSaleAt, UUID lobbyEventId) {

  /**
   * Where the event id is left for the invariant checker.
   *
   * <p>Told rather than assumed, because the working directory of the forked JVM is the Maven
   * invocation's and not this module's: a relative default lands next to whichever pom was named on
   * the command line, which is how the first green run of {@code make load-small} still ended with
   * "the run left no event id".
   */
  static final Path EVENT_ID_FILE =
      Path.of(
          System.getProperty(
              "seatrush.load.eventIdFile",
              Path.of("target", "gatling", "event-id.txt").toString()));

  private static final Duration TIMEOUT = Duration.ofSeconds(20);

  /** How far out a sale is scheduled when nothing but the run is allowed to open it. */
  private static final Duration NEVER_DURING_THIS_RUN = Duration.ofHours(4);

  /** The lobby-only sale needs a venue because every event does. Nothing ever buys from it. */
  private static final String ONE_SEAT =
      "[{\"id\":\"Z\",\"name\":\"Section Z\",\"priceCents\":1000,"
          + "\"currency\":\"EUR\",\"rows\":1,\"seatsPerRow\":1}]";

  /**
   * Two fields out of two known responses, by regex.
   *
   * <p>A JSON parser would mean a dependency of this module on one, and the only thing read here is
   * an id and a token out of a response whose shape is fixed by README section 12. The load test
   * itself parses JSON with Gatling's own checks, which is where a parser belongs.
   */
  private static final Pattern ID = Pattern.compile("\"id\"\\s*:\\s*\"([0-9a-fA-F-]{36})\"");

  private static final Pattern ACCESS_TOKEN =
      Pattern.compile("\"accessToken\"\\s*:\\s*\"([^\"]+)\"");

  /**
   * Logs in as an operator, creates the two venues, and schedules both sales.
   *
   * <p>Scheduled and not opened: {@code DRAFT} to {@code SCHEDULED} is what opens the lobby, and
   * neither sale is due for hours, so the only thing that can open one is the run.
   *
   * @throws IllegalStateException if any step fails, naming the response, because every one of them
   *     is a reason the run cannot happen at all
   */
  static BenchmarkSale open(LoadProfile profile, Venue venue) {
    try (HttpClient client =
        HttpClient.newBuilder()
            .connectTimeout(TIMEOUT)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build()) {
      String token = login(client, profile);
      Instant onSaleAt = Instant.now().plus(NEVER_DURING_THIS_RUN);
      UUID eventId = createEvent(client, profile, token, onSaleAt, venue.sectionsJson());
      schedule(client, profile, token, eventId);
      UUID lobbyEventId =
          createEvent(client, profile, token, Instant.now().plus(NEVER_DURING_THIS_RUN), ONE_SEAT);
      schedule(client, profile, token, lobbyEventId);
      remember(eventId);
      return new BenchmarkSale(eventId, onSaleAt, lobbyEventId);
    }
  }

  private static String login(HttpClient client, LoadProfile profile) {
    String body =
        post(
            client,
            profile,
            "/api/auth/demo-login",
            null,
            "{\"username\":\"load-operator\",\"admin\":true}");
    return one(
        ACCESS_TOKEN, body, "the demo login returned no token. Is the profile local or bench?");
  }

  private static UUID createEvent(
      HttpClient client, LoadProfile profile, String token, Instant onSaleAt, String sections) {
    String body =
        post(
            client,
            profile,
            "/api/admin/events",
            token,
            """
            {"name":"SeatRush load test %s","onSaleAt":"%s","sections":%s}"""
                .formatted(Instant.now(), onSaleAt, sections));
    return UUID.fromString(one(ID, body, "the event was not created: " + body));
  }

  private static void schedule(HttpClient client, LoadProfile profile, String token, UUID eventId) {
    post(client, profile, "/api/admin/events/" + eventId + "/schedule", token, "");
  }

  /** Leaves the event id where {@code scripts/load.sh} picks it up for the invariant checker. */
  private static void remember(UUID eventId) {
    try {
      Files.createDirectories(EVENT_ID_FILE.getParent());
      Files.writeString(EVENT_ID_FILE, eventId + System.lineSeparator(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("the event id could not be written to " + EVENT_ID_FILE, e);
    }
  }

  private static String post(
      HttpClient client, LoadProfile profile, String path, String token, String body) {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(profile.baseUrl() + path))
            .timeout(TIMEOUT)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
    if (token != null) {
      request.header("Authorization", "Bearer " + token);
    }
    HttpResponse<String> response;
    try {
      response = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    } catch (IOException e) {
      throw new IllegalStateException(
          "the gateway at " + profile.baseUrl() + " did not answer " + path + ": " + e.getMessage(),
          e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while setting the sale up", e);
    }
    if (response.statusCode() / 100 != 2) {
      throw new IllegalStateException(
          "POST %s answered %d: %s".formatted(path, response.statusCode(), response.body()));
    }
    return response.body();
  }

  private static String one(Pattern pattern, String body, String complaint) {
    Matcher matcher = pattern.matcher(body);
    if (!matcher.find()) {
      throw new IllegalStateException(complaint);
    }
    return matcher.group(1);
  }
}
