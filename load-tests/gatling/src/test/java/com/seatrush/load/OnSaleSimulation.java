package com.seatrush.load;

import static io.gatling.javaapi.core.CoreDsl.StringBody;
import static io.gatling.javaapi.core.CoreDsl.asLongAs;
import static io.gatling.javaapi.core.CoreDsl.atOnceUsers;
import static io.gatling.javaapi.core.CoreDsl.details;
import static io.gatling.javaapi.core.CoreDsl.doIf;
import static io.gatling.javaapi.core.CoreDsl.doIfOrElse;
import static io.gatling.javaapi.core.CoreDsl.exec;
import static io.gatling.javaapi.core.CoreDsl.global;
import static io.gatling.javaapi.core.CoreDsl.group;
import static io.gatling.javaapi.core.CoreDsl.jsonPath;
import static io.gatling.javaapi.core.CoreDsl.nothingFor;
import static io.gatling.javaapi.core.CoreDsl.pause;
import static io.gatling.javaapi.core.CoreDsl.rampUsers;
import static io.gatling.javaapi.core.CoreDsl.repeat;
import static io.gatling.javaapi.core.CoreDsl.scenario;
import static io.gatling.javaapi.http.HttpDsl.http;
import static io.gatling.javaapi.http.HttpDsl.sse;
import static io.gatling.javaapi.http.HttpDsl.status;

import io.gatling.javaapi.core.Assertion;
import io.gatling.javaapi.core.ChainBuilder;
import io.gatling.javaapi.core.ScenarioBuilder;
import io.gatling.javaapi.core.Session;
import io.gatling.javaapi.core.Simulation;
import io.gatling.javaapi.http.HttpProtocolBuilder;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * The on-sale of README section 18.1: a flash sale with far more buyers than seats.
 *
 * <p>Three populations, each with one job. <b>Buyers</b> join the waiting room, wait for their
 * turn, reach for a block of adjacent seats, retry when they lose the race, and either pay or walk
 * away. <b>Watchers</b> wait on the pushed stream of the waiting room and never buy.
 * <b>Spectators</b> hold the seat map stream open and never buy either. The two stream populations
 * are the concurrent SSE connections of README section 15; the buyers are every latency figure in
 * it.
 *
 * <p>Most buyers are supposed to fail, and that is the scenario rather than a defect: a venue of
 * 2,000 seats cannot serve 5,000 buyers, so the question worth asking is whether refusing the other
 * three thousand is as fast and as correct as serving the winners. A conflict is therefore an
 * expected status and not an error, and the assertions below are about requests the system could
 * not answer at all.
 *
 * <p>What this simulation does not decide is whether the run was correct. Gatling sees responses;
 * overselling, a confirmed order with no ticket and a hold that outlived its TTL are all invisible
 * from here and belong to the invariant checker (README section 17.1). {@code make load-small} is
 * the two of them in that order, and a run is green only if both are.
 */
@SuppressWarnings("this-escape")
public class OnSaleSimulation extends Simulation {

  /** The statuses a checkout stops at, from README section 11.2. */
  private static final Set<String> TERMINAL = Set.of("CONFIRMED", "FAILED");

  /**
   * What {@code POST /api/orders} is allowed to answer here (README section 12.6).
   *
   * <p>202 started the checkout and 503 says a dependency was briefly away and the request is safe
   * to repeat, which is what the retry below does. The other two are the hold being gone: 409 for a
   * hold another order already owns and 410 for one that expired while its buyer was queueing, and
   * both end that buyer's run rather than the system's.
   *
   * <p>409 on a retry is worth knowing about and is visible in the report, because it is the one
   * answer the contract does not predict. A 503 is promised to be safe to repeat with the same
   * {@code Idempotency-Key}, and under saturation the repeat is refused: the order id is minted per
   * attempt rather than derived from the key, so the second attempt asks inventory to freeze a hold
   * the first attempt already froze for an id that was thrown away. README section 15 asks for no
   * checkout to fail beyond client retries, and that one does.
   */
  private static final List<Integer> ALLOWED_ON_CHECKOUT = List.of(202, 409, 410, 503);

  private final LoadProfile profile = LoadProfile.fromSystemProperties();
  private final Venue venue = Venue.of(profile);

  /**
   * The sale, created before the injection starts.
   *
   * <p>Written in {@link #before()} and read by the lambdas that build the urls below, which is why
   * it is volatile: Gatling builds the scenarios on one thread and runs them on others.
   */
  private volatile BenchmarkSale sale;

  private final HttpProtocolBuilder httpProtocol =
      http.baseUrl(profile.baseUrl())
          .acceptHeader("application/json")
          .contentTypeHeader("application/json")
          .userAgentHeader("seatrush-gatling")
          .disableCaching();

  @Override
  public void before() {
    System.out.print(profile.describe());
    sale = BenchmarkSale.open(profile, venue);
    System.out.printf(
        "  sale       %s, opened by this run after %s and due at %s%n",
        sale.eventId(), profile.lobbySeconds(), sale.onSaleAt());
    System.out.printf("  lobby      %s, which never opens%n%n", sale.lobbyEventId());
  }

  @Override
  public void after() {
    if (sale != null) {
      System.out.printf(
          "%nthis run is not a verdict. What was bought is checked separately:%n"
              + "  make check CHECK_FLAGS=\"--event %s\"%n",
          sale.eventId());
    }
  }

  // ------------------------------------------------------------------ the buyer

  private final ScenarioBuilder buyer =
      scenario("buyer")
          .exec(identify("bench"))
          .exec(login())
          .exec(joinTheQueue())
          .exec(waitForAdmission())
          .doIf("#{admission.exists()}")
          .then(takeSeats().exec(doIf("#{holdId.exists()}").then(payOrWalkAway())));

  private ChainBuilder joinTheQueue() {
    return joinTheQueueOf(() -> sale.eventId());
  }

  private ChainBuilder joinTheQueueOf(java.util.function.Supplier<Object> event) {
    return exec(
        http("queue join")
            .post(session -> "/api/events/" + event.get() + "/queue")
            .header("Authorization", "Bearer #{jwt}")
            // 409 is a sale that sold out before this buyer even joined, which is a sale doing its
            // job rather than a request that failed.
            .check(status().in(200, 409)));
  }

  /**
   * The wait, read rather than pushed (README section 12.3).
   *
   * <p>A real client opens {@code .../queue/stream} and is told when it is in. This polls {@code
   * .../queue/me} instead, which is the fallback the same section documents and which answers with
   * the same fields, and the reason is worth writing down rather than hiding: the injector could
   * not read the pushed messages reliably. Gatling's SSE checks never extracted anything from a
   * frame here, so a buyer waiting on the stream could not tell an admission from a position
   * update, and a load test that cannot read the answer is a load test that measures nothing.
   *
   * <p>So the stream is exercised by the watchers below, whose job is the connection rather than
   * the message, and the buyer reads its place. Neither choice changes a figure this run reports:
   * the latencies of README section 15 are the join, the hold and the checkout, and none of them is
   * the wait.
   */
  private ChainBuilder waitForAdmission() {
    long attempts =
        Math.max(1, profile.admissionWait().toMillis() / pollInterval(profile.queuePollInterval()));
    return exec(
        asLongAs(
                session ->
                    !session.contains("admission")
                        && stillWaiting(session)
                        && loops(session, "wait") < attempts,
                "wait",
                true)
            .on(
                pause(profile.queuePollInterval())
                    .exec(
                        http("queue status")
                            .get(session -> "/api/events/" + sale.eventId() + "/queue/me")
                            .header("Authorization", "Bearer #{jwt}")
                            // 404 is a sale that has finished with this buyer: the slot is gone and
                            // there is nothing left to wait for.
                            .check(status().in(200, 404))
                            .check(status().saveAs("queueStatus"))
                            .check(jsonPath("$.state").optional().saveAs("queueState"))
                            .check(jsonPath("$.admissionToken").optional().saveAs("admission")))));
  }

  /**
   * The race, as README section 18.1 describes it: one block, three more tries on a conflict.
   *
   * <p>The retries are the point and not politeness. A buyer who lost four adjacent seats by a
   * millisecond is told exactly which ones went, and what a client does next is reach for different
   * ones; a load test that gave up after one attempt would never produce the second wave of
   * contention that follows every sold block.
   */
  private ChainBuilder takeSeats() {
    return repeat(profile.holdAttempts(), "attempt")
        .on(
            doIf(session -> !session.contains("holdId"))
                .then(
                    exec(session -> session.set("seatIds", venue.blockJson(randomOf(session))))
                        .exec(
                            http("hold")
                                .post(session -> "/api/events/" + sale.eventId() + "/holds")
                                .header("Authorization", "Bearer #{jwt}")
                                .header("X-Admission-Token", "#{admission}")
                                .header("Idempotency-Key", session -> UUID.randomUUID().toString())
                                .body(StringBody("{\"seatIds\":#{seatIds}}"))
                                // 201 took the seats and 409 did not, which are the two answers
                                // this endpoint has under contention (README section 12.6).
                                .check(status().in(201, 409))
                                .check(jsonPath("$.holdId").optional().saveAs("holdId")))));
  }

  /** One buyer in ten holds seats and never comes back, which is what the sweeper is for. */
  private ChainBuilder payOrWalkAway() {
    return doIfOrElse(session -> randomOf(session).nextDouble() < profile.abandonRate())
        .then(exec(session -> session.set("abandoned", true)))
        .orElse(checkout());
  }

  /**
   * The checkout, from the command to a terminal order.
   *
   * <p>A group, because the figure README section 15 asks for is the wall clock from asking to
   * being told, and that spans a command, a SAGA across four services and however many polls it
   * took. The requests inside it are in the report as well: {@code order} is the 202 and {@code
   * order status} is one poll.
   *
   * <p>The polling is a client reading {@code GET /api/orders/{id}} rather than the system telling
   * it anything, so what it produces is quantised by the interval: a checkout that finished a
   * millisecond after a poll is measured as a whole interval longer. That is why it is reported and
   * not gated.
   */
  private ChainBuilder checkout() {
    long polls =
        Math.max(
            1, profile.checkoutWait().toMillis() / pollInterval(profile.checkoutPollInterval()));
    return group("checkout")
        .on(
            exec(session -> session.set("orderKey", UUID.randomUUID().toString()))
                .exec(startCheckout("order"))
                .exec(
                    asLongAs(
                            session ->
                                !session.contains("orderId")
                                    && loops(session, "retry") < profile.orderRetries(),
                            "retry",
                            true)
                        .on(pause(Duration.ofSeconds(1)).exec(startCheckout("order retry"))))
                .exec(
                    doIf("#{orderId.exists()}")
                        .then(
                            asLongAs(
                                    session -> !settled(session) && loops(session, "poll") < polls,
                                    "poll",
                                    true)
                                .on(
                                    pause(profile.checkoutPollInterval())
                                        .exec(
                                            http("order status")
                                                .get("/api/orders/#{orderId}")
                                                .header("Authorization", "Bearer #{jwt}")
                                                .check(status().is(200))
                                                .check(
                                                    jsonPath("$.status")
                                                        .saveAs("orderStatus")))))));
  }

  /**
   * One attempt at starting the checkout, under the name the report should count it as.
   *
   * <p>503 is an allowed answer and the retry above is why. README section 12.6 defines it as a
   * dependency being briefly away and says the request is safe to repeat with the same {@code
   * Idempotency-Key}, and section 15 asks for no checkout to fail "beyond client retries", so a
   * load test that gave up on the first one would be holding the system to a standard its own
   * contract does not set. The key is generated once per buyer and reused, which means the retry is
   * also a test of invariant I5 under real contention: if the first attempt did create the order
   * before the connection broke, the second has to answer with that order and not a second one.
   *
   * <p>The retries are named separately so the report still shows them. Folded into {@code order}
   * they would be invisible, and how often a run had to retry is exactly the saturation signal a
   * benchmark is read for.
   */
  private ChainBuilder startCheckout(String name) {
    return exec(
        http(name)
            .post("/api/orders")
            .header("Authorization", "Bearer #{jwt}")
            .header("X-Admission-Token", "#{admission}")
            .header("Idempotency-Key", "#{orderKey}")
            .body(StringBody("{\"holdId\":\"#{holdId}\"}"))
            .check(status().in(ALLOWED_ON_CHECKOUT))
            .check(jsonPath("$.orderId").optional().saveAs("orderId"))
            .check(jsonPath("$.status").optional().saveAs("orderStatus")));
  }

  // ------------------------------------------------------------------ the doors

  /**
   * The one user whose job is to open the sale, at the instant the lobby has finished filling.
   *
   * <p>{@code POST /api/admin/events/{id}/open} and not the clock, and that is deliberate twice
   * over. README section 12.2 built this endpoint for demos and benchmarks, and a benchmark is
   * exactly what wants it: the whole shape of section 18.1 is measured from the moment the doors
   * open, and a run that waited on {@code onSaleAt} would start measuring whenever a poll happened
   * to notice. This way the lobby fills, one request opens the sale, and the rush arrives, all on
   * one timeline.
   *
   * <p>It used to be the only way the sale opened at all, which is how this simulation found that
   * nothing moved a {@code SCHEDULED} event to {@code ON_SALE} on its own: the first run waited
   * forty seconds for a sale that had been due for a quarter of an hour. The sale opener of README
   * section 6.3 now does it on the clock, within its interval of {@code onSaleAt}. This scenario
   * stays because the reason above is the real one: a benchmark wants the instant it starts
   * measuring from to be a request it made, not a poll somewhere else that happened to notice.
   *
   * <p>Which is also why {@link BenchmarkSale} schedules the sale hours out. The two openers used
   * to be due at the same instant and raced, and the one that lost was answered {@code 400}. The
   * status is checked and not tolerated on purpose: with the clock out of reach, anything but
   * {@code 200} here means a second thing decided when this run started.
   */
  private final ScenarioBuilder opener =
      scenario("opener")
          .exec(exec(session -> session.set("username", "load-operator")))
          .exec(
              http("login")
                  .post("/api/auth/demo-login")
                  .body(StringBody("{\"username\":\"#{username}\",\"admin\":true}"))
                  .check(status().is(200))
                  .check(jsonPath("$.accessToken").saveAs("jwt")))
          .pause(profile.lobbySeconds())
          .exec(
              http("open the sale")
                  .post(session -> "/api/admin/events/" + sale.eventId() + "/open")
                  .header("Authorization", "Bearer #{jwt}")
                  .check(status().is(200)));

  // ------------------------------------------------------------------ the two stream populations

  /**
   * A client waiting on the pushed stream of the waiting room (README section 12.3).
   *
   * <p>It joins, opens the stream and holds it, which is the whole job: README section 15 asks for
   * 200,000 concurrent queue connections, and that is a number about sockets, heap and the pod that
   * has to fan out to them rather than about anything a message says.
   *
   * <p>It waits in the lobby of the second sale rather than the one being bought from, for the
   * reason {@link BenchmarkSale} gives: this stream ends itself on admission, so on a sale that is
   * admitting there is no such thing as a connection that stays.
   */
  private final ScenarioBuilder watcher =
      scenario("waiting room watcher")
          .exec(identify("watch"))
          .exec(login())
          .exec(joinTheQueueOf(() -> sale.lobbyEventId()))
          .exec(
              sse("waiting room stream")
                  .get(session -> "/api/events/" + sale.lobbyEventId() + "/queue/stream")
                  .header("Authorization", "Bearer #{jwt}"))
          .exec(holdTheStream())
          .exec(sse("waiting room stream").close());

  /** And a client watching the venue fill, which is the other half of the same target. */
  private final ScenarioBuilder spectator =
      scenario("spectator")
          .exec(identify("map"))
          .exec(login())
          .exec(
              http("seat map snapshot")
                  .get(session -> "/api/events/" + sale.eventId() + "/seatmap")
                  .header("Authorization", "Bearer #{jwt}")
                  .check(status().is(200))
                  .check(jsonPath("$.version").saveAs("version")))
          .exec(
              sse("seat map stream")
                  .get(session -> "/api/events/" + sale.eventId() + "/seatmap/stream")
                  .header("Authorization", "Bearer #{jwt}"))
          .exec(holdTheStream())
          .exec(sse("seat map stream").close());

  /**
   * Staying connected for the sale, in steps rather than in one pause.
   *
   * <p>Steps so that {@code maxDuration} can stop a run without waiting out a single long pause,
   * and so a stream that the server closed is noticed when the close is attempted rather than
   * never.
   */
  private ChainBuilder holdTheStream() {
    long steps = Math.max(1, profile.watchFor().toSeconds() / 10);
    return exec(repeat((int) steps, "watching").on(pause(Duration.ofSeconds(10))));
  }

  // ------------------------------------------------------------------ the run

  {
    setUp(
            // Already waiting when the doors open, which is the point of a lobby: it is shuffled,
            // so arriving early inside this window buys nothing (README section 6.2).
            buyer.injectOpen(
                atOnceUsers(profile.lobbyUsers()),
                // And the rush that arrives once the sale is open.
                nothingFor(profile.lobbySeconds()),
                rampUsers(profile.arrivingUsers()).during(profile.arrivalWindow())),
            opener.injectOpen(atOnceUsers(1)),
            watcher.injectOpen(rampUsers(profile.watchers()).during(Duration.ofSeconds(10))),
            spectator.injectOpen(rampUsers(profile.spectators()).during(Duration.ofSeconds(10))))
        .protocols(httpProtocol)
        .maxDuration(profile.maxDuration())
        .assertions(assertions());
  }

  /**
   * What makes a run fail.
   *
   * <p>One assertion always holds: nothing the system could not answer. Every refusal this scenario
   * expects is an allowed status on the request that produces it, so a failure here is a timeout, a
   * 5xx or a status nobody designed for, and that is worth gating at any size.
   *
   * <p>The latency targets of README section 15 are the other kind, and they gate the benchmark
   * only. They are figures about the environment of section 18.2: a laptop injecting five thousand
   * buyers at the stack it is also running has no business being held to them, and a gate that
   * fails for that reason is a gate people learn to ignore. {@code full} turns them on, and so does
   * {@code -Dseatrush.load.assertTargets=true} on any profile.
   *
   * <p>Two of the five targets, because they are the two measured by one request each with no poll
   * and no pause inside them. The checkout figure is quantised by its poll interval, and seat map
   * propagation is not measured by this injector at all.
   */
  private List<Assertion> assertions() {
    List<Assertion> assertions = new ArrayList<>();
    assertions.add(global().failedRequests().count().is(0L));
    if (profile.assertTargets()) {
      assertions.add(details("queue join").responseTime().percentile4().lt(100));
      assertions.add(details("hold").responseTime().percentile4().lt(50));
    }
    return assertions;
  }

  // ------------------------------------------------------------------ helpers

  /** A name and a seed per virtual user, both derived from the id Gatling already gave it. */
  private ChainBuilder identify(String prefix) {
    return exec(
        session ->
            session
                .set("username", prefix + "-" + session.userId())
                .set("random", new Random(profile.seed() + session.userId())));
  }

  /**
   * The demo login of README section 12.1, which is the only way a token exists locally.
   *
   * <p>Saved as {@code jwt} and not as {@code token}, which is not a style choice: the admission is
   * also a token, and one run of this simulation was spent on a buyer that read its own login back
   * as its admission and never noticed.
   */
  private ChainBuilder login() {
    return exec(
        http("login")
            .post("/api/auth/demo-login")
            .body(StringBody("{\"username\":\"#{username}\",\"admin\":false}"))
            .check(status().is(200))
            .check(jsonPath("$.accessToken").saveAs("jwt")));
  }

  private static long pollInterval(Duration interval) {
    return Math.max(1, interval.toMillis());
  }

  /** Whether this buyer still has a place in the waiting room to wait for. */
  private static boolean stillWaiting(Session session) {
    return !"404".equals(session.getString("queueStatus"));
  }

  private static boolean settled(Session session) {
    return TERMINAL.contains(session.getString("orderStatus"));
  }

  /**
   * How many times a loop has gone round.
   *
   * <p>Gatling sets the counter when the loop is entered, so the first evaluation of the condition
   * happens before it exists. Reading it defensively is what keeps that from being an exception on
   * every buyer.
   */
  private static long loops(Session session, String counter) {
    return session.contains(counter) ? session.getInt(counter) : 0;
  }

  private static Random randomOf(Session session) {
    return session.get("random");
  }
}
