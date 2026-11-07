package com.seatrush.load;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The size of one run (README section 18.1).
 *
 * <p>Two presets and nothing in between. {@code full} is the benchmark that section 18.1 specifies,
 * 200,000 buyers against a venue of 20,000 seats, which needs an injector of its own and a cluster
 * to point it at. {@code small} is the same shape at a hundredth of the size, 5,000 buyers against
 * 2,000 seats, which is what {@code make load-small} runs on a laptop beside the stack it is
 * testing. Both sell out, which is the only thing about the shape that matters: a run that leaves
 * seats unsold never exercises the contention the whole system exists to survive.
 *
 * <p>Every knob can be overridden on its own with {@code -Dseatrush.load.<name>=}, so a run halfway
 * between the two does not need a third preset. The names below are the names of those properties.
 *
 * @param baseUrl where the gateway answers
 * @param sections how many sections the venue has, each one a rectangle of rows by seatsPerRow
 * @param lobbyUsers buyers who are already waiting when the sale opens
 * @param arrivingUsers buyers who join after it has opened
 * @param lobbySeconds how long the lobby is given to fill before the sale opens, which is also what
 *     {@code onSaleAt} is set to
 * @param arrivalWindow how long the late arrivals take to all have joined
 * @param spectators clients watching the seat map and buying nothing
 * @param watchers clients waiting on the pushed stream of the waiting room and buying nothing
 * @param hotSections how many sections most buyers want, which is what creates real contention
 * @param hotSectionShare the share of buyers who want one of them
 * @param maxSeatsPerHold the largest block of adjacent seats a buyer asks for
 * @param holdAttempts tries per buyer, which is the first one plus the three retries of section
 *     18.1
 * @param orderRetries how often a buyer repeats a checkout the system answered 503 to, with the
 *     same idempotency key. README section 12.6 says that answer is safe to repeat and section 15
 *     asks for no checkout to fail beyond client retries, so not repeating it would hold the system
 *     to a standard its own contract does not set
 * @param abandonRate the share of buyers who hold seats and never pay, so hold expiry is exercised
 * @param checkoutPollInterval how often a buyer asks whether its order has settled. It is the
 *     resolution of the checkout figure and, multiplied by the buyers, a request rate of its own:
 *     this is an injector reading rather than the system pushing, so it is deliberately slow
 * @param queuePollInterval the same trade-off for the wait, where the buyers are far more numerous
 * @param watchFor how long a spectator and a probe stay on their stream, which is what bounds the
 *     run: the buyers finish when they are served or refused, and these two would otherwise sit
 *     there until maxDuration cut them off
 * @param assertTargets whether the latency targets of README section 15 gate the run. On for the
 *     benchmark, off for the small profile, because those targets are about the environment of
 *     section 18.2 and not about a laptop running the stack it is measuring
 * @param seed what the seat choices are derived from, so two runs contend over the same seats
 */
record LoadProfile(
    String baseUrl,
    int sections,
    int rows,
    int seatsPerRow,
    int lobbyUsers,
    int arrivingUsers,
    Duration lobbySeconds,
    Duration arrivalWindow,
    int spectators,
    int watchers,
    int hotSections,
    double hotSectionShare,
    int maxSeatsPerHold,
    int holdAttempts,
    int orderRetries,
    double abandonRate,
    Duration admissionWait,
    Duration checkoutWait,
    Duration checkoutPollInterval,
    Duration queuePollInterval,
    Duration watchFor,
    Duration maxDuration,
    boolean assertTargets,
    long seed) {

  /** Accepts 250ms, 30s, 5m and 1h, and a bare number as seconds. */
  private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)?");

  private static final String PREFIX = "seatrush.load.";

  /**
   * The benchmark of README section 18.1, at the size it specifies.
   *
   * <p>Ten sections of 40 by 50 is 20,000 seats, and 200,000 buyers asking for one to four of them
   * is ten times the demand the venue can meet. That ratio is the scenario: the losers are most of
   * the traffic, and a system is only interesting under load if refusing them is as fast as serving
   * the winners.
   */
  static LoadProfile full() {
    return new LoadProfile(
        "http://localhost:8080",
        10,
        40,
        50,
        50_000,
        150_000,
        Duration.ofSeconds(60),
        Duration.ofSeconds(60),
        2_000,
        20_000,
        2,
        0.70,
        4,
        4,
        3,
        0.10,
        Duration.ofMinutes(5),
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofMinutes(10),
        Duration.ofMinutes(30),
        true,
        20261107L);
  }

  /**
   * The same shape at a hundredth of the size, which is what a laptop can inject.
   *
   * <p>Ten sections of 10 by 20 is 2,000 seats against 5,000 buyers, so the demand still outruns
   * the venue by the same order and the sale still ends in a sold out event. The waiting room is
   * shorter rather than smaller in proportion, because the admission rate is a fixed 500 a second:
   * a lobby of 1,500 drains in three seconds whatever else is configured.
   */
  static LoadProfile small() {
    return new LoadProfile(
        "http://localhost:8080",
        10,
        10,
        20,
        1_500,
        3_500,
        Duration.ofSeconds(20),
        Duration.ofSeconds(30),
        200,
        300,
        2,
        0.70,
        4,
        4,
        3,
        0.10,
        Duration.ofMinutes(2),
        Duration.ofSeconds(30),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        Duration.ofMinutes(2),
        Duration.ofMinutes(10),
        false,
        20261107L);
  }

  /** The preset named by {@code -Dseatrush.load.profile}, with every knob overridden in turn. */
  static LoadProfile fromSystemProperties() {
    String name = System.getProperty(PREFIX + "profile", "small").strip().toLowerCase(Locale.ROOT);
    LoadProfile preset =
        switch (name) {
          case "small" -> small();
          case "full" -> full();
          default ->
              throw new IllegalArgumentException(
                  "unknown load profile: " + name + ". It is small or full");
        };
    return preset.withOverrides();
  }

  int seatCount() {
    return sections * rows * seatsPerRow;
  }

  int buyers() {
    return lobbyUsers + arrivingUsers;
  }

  /** How many clients hold a stream open, which is the connection target of README section 15. */
  int streamClients() {
    return spectators + watchers;
  }

  /** One line for the head of the report, so a run says what it was before it says how it went. */
  String describe() {
    return """
        SeatRush load test (README section 18.1)
          target     %s
          venue      %d seats in %d sections of %d by %d, %d of them in demand
          buyers     %d waiting when the sale opens, %d arriving over %s
          watching   %d on the seat map stream, %d on the waiting room stream
          behaviour  up to %d adjacent seats, %d tries, %.0f%% abandon after holding
          seed       %d
        """
        .formatted(
            baseUrl,
            seatCount(),
            sections,
            rows,
            seatsPerRow,
            hotSections,
            lobbyUsers,
            arrivingUsers,
            arrivalWindow,
            spectators,
            watchers,
            maxSeatsPerHold,
            holdAttempts,
            abandonRate * 100,
            seed);
  }

  private LoadProfile withOverrides() {
    return new LoadProfile(
        text("baseUrl", baseUrl),
        count("sections", sections),
        count("rows", rows),
        count("seatsPerRow", seatsPerRow),
        count("lobbyUsers", lobbyUsers),
        count("arrivingUsers", arrivingUsers),
        duration("lobbySeconds", lobbySeconds),
        duration("arrivalWindow", arrivalWindow),
        count("spectators", spectators),
        count("watchers", watchers),
        count("hotSections", hotSections),
        share("hotSectionShare", hotSectionShare),
        count("maxSeatsPerHold", maxSeatsPerHold),
        count("holdAttempts", holdAttempts),
        count("orderRetries", orderRetries),
        share("abandonRate", abandonRate),
        duration("admissionWait", admissionWait),
        duration("checkoutWait", checkoutWait),
        duration("checkoutPollInterval", checkoutPollInterval),
        duration("queuePollInterval", queuePollInterval),
        duration("watchFor", watchFor),
        duration("maxDuration", maxDuration),
        flag("assertTargets", assertTargets),
        number("seed", seed));
  }

  private static String text(String name, String fallback) {
    String value = System.getProperty(PREFIX + name);
    return value == null || value.isBlank() ? fallback : value.strip();
  }

  private static int count(String name, int fallback) {
    long value = number(name, fallback);
    if (value < 0 || value > Integer.MAX_VALUE) {
      throw new IllegalArgumentException(PREFIX + name + " is out of range: " + value);
    }
    return (int) value;
  }

  private static long number(String name, long fallback) {
    String value = System.getProperty(PREFIX + name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    try {
      return Long.parseLong(value.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(PREFIX + name + " is not a number: " + value, e);
    }
  }

  private static double share(String name, double fallback) {
    String value = System.getProperty(PREFIX + name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    double share;
    try {
      share = Double.parseDouble(value.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(PREFIX + name + " is not a number: " + value, e);
    }
    if (share < 0 || share > 1) {
      throw new IllegalArgumentException(PREFIX + name + " is a share of 1, but was " + share);
    }
    return share;
  }

  private static boolean flag(String name, boolean fallback) {
    String value = System.getProperty(PREFIX + name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    return Boolean.parseBoolean(value.strip());
  }

  private static Duration duration(String name, Duration fallback) {
    String value = System.getProperty(PREFIX + name);
    if (value == null || value.isBlank()) {
      return fallback;
    }
    Matcher matcher = DURATION.matcher(value.strip().toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          PREFIX + name + " is not a duration: " + value + ". Write it as 250ms, 30s, 5m or 1h");
    }
    long amount = Long.parseLong(matcher.group(1));
    String unit = matcher.group(2) == null ? "s" : matcher.group(2);
    return switch (unit) {
      case "ms" -> Duration.ofMillis(amount);
      case "m" -> Duration.ofMinutes(amount);
      case "h" -> Duration.ofHours(amount);
      default -> Duration.ofSeconds(amount);
    };
  }
}
