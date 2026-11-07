package com.seatrush.invariants;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Where the checker reads from, and how strict it is about what it finds.
 *
 * <p>Every value has a default that matches the local stack of README section 23, can be overridden
 * by the environment variable that stack is itself configured with, and can be overridden again by
 * a flag. That order is what lets one binary check a laptop, a port forwarded cluster and a CI job:
 * the environment carries the addresses, and a flag carries the one thing whoever runs it wants
 * different.
 *
 * @param postgresHost the server the three databases live on, since locally there is one
 * @param settleTimeout how long to wait for every checkout to finish before reading, or {@link
 *     Duration#ZERO} to read a system that is still working
 * @param grace how late a sweeper or a scheduler may be before being late counts as a violation
 * @param examples how many offending rows each failed check prints before it summarises the rest
 * @param eventId the one event to check, or null for every event in the catalog
 */
record CheckerOptions(
    String postgresHost,
    int postgresPort,
    String username,
    String password,
    String inventoryDatabase,
    String orderDatabase,
    String paymentDatabase,
    String redisHost,
    int redisPort,
    Duration settleTimeout,
    Duration grace,
    int examples,
    UUID eventId) {

  /** Accepts 250ms, 30s, 5m and 1h, and a bare number as seconds. */
  private static final Pattern DURATION = Pattern.compile("(\\d+)(ms|s|m|h)?");

  /**
   * The flags, each one next to the environment variable it overrides.
   *
   * <p>One table rather than a switch, so an unknown flag is refused by the same thing that
   * documents the known ones. A checker that ignored a misspelled flag would report on a system
   * nobody asked about, which is worse than not running at all.
   */
  private static final Map<String, String> FLAGS =
      Map.ofEntries(
          Map.entry("--postgres-host", "POSTGRES_HOST"),
          Map.entry("--postgres-port", "POSTGRES_PORT"),
          Map.entry("--user", "POSTGRES_USER"),
          Map.entry("--password", "POSTGRES_PASSWORD"),
          Map.entry("--inventory-db", "POSTGRES_INVENTORY_DB"),
          Map.entry("--order-db", "POSTGRES_ORDER_DB"),
          Map.entry("--payment-db", "POSTGRES_PAYMENT_DB"),
          Map.entry("--redis-host", "REDIS_HOST"),
          Map.entry("--redis-port", "REDIS_PORT"),
          Map.entry("--settle-timeout", "SEATRUSH_CHECK_SETTLE_TIMEOUT"),
          Map.entry("--grace", "SEATRUSH_CHECK_GRACE"),
          Map.entry("--examples", "SEATRUSH_CHECK_EXAMPLES"),
          Map.entry("--event", "SEATRUSH_CHECK_EVENT"));

  /** The flags that ask for the usage text instead of a run. */
  private static final Set<String> HELP = Set.of("--help", "-h", "help");

  /** Whether these arguments are a request for the usage text rather than for a check. */
  static boolean isHelp(String... arguments) {
    for (String argument : arguments) {
      if (HELP.contains(argument)) {
        return true;
      }
    }
    return false;
  }

  /**
   * The defaults of the environment with the flags applied.
   *
   * @throws IllegalArgumentException on an unknown flag, a missing value or a value that does not
   *     parse
   */
  static CheckerOptions parse(String... arguments) {
    Map<String, String> given = new LinkedHashMap<>();
    for (int i = 0; i < arguments.length; i++) {
      String flag = arguments[i];
      if (!FLAGS.containsKey(flag)) {
        throw new IllegalArgumentException(
            flag.startsWith("--") ? "unknown flag: " + flag : "not a flag: " + flag);
      }
      if (i + 1 >= arguments.length) {
        throw new IllegalArgumentException(flag + " needs a value");
      }
      given.put(flag, arguments[++i]);
    }

    return new CheckerOptions(
        value(given, "--postgres-host", "localhost"),
        port(given, "--postgres-port", "5432"),
        value(given, "--user", "seatrush"),
        value(given, "--password", "seatrush"),
        value(given, "--inventory-db", "seatrush_inventory"),
        value(given, "--order-db", "seatrush_order"),
        value(given, "--payment-db", "seatrush_payment"),
        value(given, "--redis-host", "localhost"),
        port(given, "--redis-port", "6379"),
        // A minute, because this is the normal case after a load test: the injector has stopped,
        // a few hundred checkouts are still waiting for their capture, and they finish in seconds.
        duration(given, "--settle-timeout", "60s"),
        // Five seconds covers the slowest of the three things that are allowed to be behind: the
        // hold sweeper runs every second and the SAGA scheduler every few. The reconciler of
        // README section 9.6 runs every thirty, which is why the wait above exists as well.
        duration(given, "--grace", "5s"),
        count(given, "--examples", "10"),
        uuid(given, "--event"));
  }

  String inventoryUrl() {
    return jdbcUrl(inventoryDatabase);
  }

  String orderUrl() {
    return jdbcUrl(orderDatabase);
  }

  String paymentUrl() {
    return jdbcUrl(paymentDatabase);
  }

  String redisUri() {
    return "redis://" + redisHost + ":" + redisPort;
  }

  /**
   * Whether the checker insists on a system where nothing is in flight.
   *
   * <p>Zero is the deliberate way to ask for the other mode: read what is there right now and check
   * whatever can be checked of it, which is what a look taken in the middle of a load test wants.
   * Any other value means the checks that compare the two halves of a finished checkout have to be
   * answerable, and the checker fails rather than skipping them quietly.
   */
  boolean requiresSettled() {
    return !settleTimeout.isZero();
  }

  private String jdbcUrl(String database) {
    return "jdbc:postgresql://" + postgresHost + ":" + postgresPort + "/" + database;
  }

  static String usage() {
    return """
        Usage: invariant-checker [flags]

        Reads the three databases and the Redis of a SeatRush deployment and verifies the
        invariants of README section 4. Prints a report, and exits 0 if every invariant
        holds, 1 if any of them is violated, and 2 if the check could not be made at all.

        Flags, each defaulting to the environment variable in brackets:
          --postgres-host <host>   [POSTGRES_HOST]           default localhost
          --postgres-port <port>   [POSTGRES_PORT]           default 5432
          --user <name>            [POSTGRES_USER]           default seatrush
          --password <secret>      [POSTGRES_PASSWORD]       default seatrush
          --inventory-db <name>    [POSTGRES_INVENTORY_DB]   default seatrush_inventory
          --order-db <name>        [POSTGRES_ORDER_DB]       default seatrush_order
          --payment-db <name>      [POSTGRES_PAYMENT_DB]     default seatrush_payment
          --redis-host <host>      [REDIS_HOST]              default localhost
          --redis-port <port>      [REDIS_PORT]              default 6379
          --settle-timeout <time>  how long to wait for every checkout to reach a terminal
                                   state before reading. 0 reads a system that is still
                                   working, and skips the checks that need a finished one
                                   [SEATRUSH_CHECK_SETTLE_TIMEOUT]      default 60s
          --grace <time>           how late a sweeper or a scheduler may be before being
                                   late is a violation
                                   [SEATRUSH_CHECK_GRACE]               default 5s
          --examples <n>           offending rows printed per failed check
                                   [SEATRUSH_CHECK_EXAMPLES]            default 10
          --event <uuid>           check this event alone instead of the whole catalog
                                   [SEATRUSH_CHECK_EVENT]
          --help                   print this and exit

        Durations are written as 250ms, 30s, 5m or 1h. A bare number means seconds.
        """;
  }

  /** The flag, else its environment variable, else the built in default. */
  private static String value(Map<String, String> given, String flag, String fallback) {
    String argument = given.get(flag);
    if (argument != null) {
      return argument;
    }
    String variable = System.getenv(FLAGS.get(flag));
    return variable == null || variable.isBlank() ? fallback : variable;
  }

  private static int port(Map<String, String> given, String flag, String fallback) {
    String raw = value(given, flag, fallback);
    int port = number(flag, raw);
    if (port < 1 || port > 65535) {
      throw new IllegalArgumentException(flag + " is not a port: " + raw);
    }
    return port;
  }

  private static int count(Map<String, String> given, String flag, String fallback) {
    String raw = value(given, flag, fallback);
    int count = number(flag, raw);
    if (count < 0) {
      throw new IllegalArgumentException(flag + " cannot be negative: " + raw);
    }
    return count;
  }

  private static int number(String flag, String raw) {
    try {
      return Integer.parseInt(raw.strip());
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(flag + " is not a number: " + raw, e);
    }
  }

  private static Duration duration(Map<String, String> given, String flag, String fallback) {
    String raw = value(given, flag, fallback);
    Matcher matcher = DURATION.matcher(raw.strip().toLowerCase(Locale.ROOT));
    if (!matcher.matches()) {
      throw new IllegalArgumentException(
          flag + " is not a duration: " + raw + ". Write it as 250ms, 30s, 5m or 1h");
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

  private static UUID uuid(Map<String, String> given, String flag) {
    String raw = value(given, flag, "");
    if (raw.isBlank()) {
      return null;
    }
    try {
      return UUID.fromString(raw.strip());
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException(flag + " is not a uuid: " + raw, e);
    }
  }
}
