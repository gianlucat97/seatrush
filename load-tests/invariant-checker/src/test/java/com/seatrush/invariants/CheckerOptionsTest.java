package com.seatrush.invariants;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The configuration of the tool, which is the part a person gets wrong at two in the morning.
 *
 * <p>Nothing here asserts a default that comes from the environment, because the environment is the
 * one input a test cannot own: a shell with {@code POSTGRES_PORT} set would otherwise make the
 * suite fail for a reason that has nothing to do with the code. What is asserted is that a flag
 * wins, that a bad value is refused rather than guessed at, and that the url is assembled the way
 * the driver needs it.
 */
class CheckerOptionsTest {

  @Test
  void aFlagWinsOverEverythingElse() {
    CheckerOptions options =
        CheckerOptions.parse(
            "--postgres-host", "db.internal",
            "--postgres-port", "6543",
            "--user", "reader",
            "--password", "secret",
            "--inventory-db", "inv",
            "--order-db", "ord",
            "--payment-db", "pay",
            "--redis-host", "cache.internal",
            "--redis-port", "6380");

    assertThat(options.inventoryUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/inv");
    assertThat(options.orderUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/ord");
    assertThat(options.paymentUrl()).isEqualTo("jdbc:postgresql://db.internal:6543/pay");
    assertThat(options.redisUri()).isEqualTo("redis://cache.internal:6380");
    assertThat(options.username()).isEqualTo("reader");
    assertThat(options.password()).isEqualTo("secret");
  }

  @Test
  void readsEveryShapeOfDuration() {
    assertThat(CheckerOptions.parse("--grace", "250ms").grace()).isEqualTo(Duration.ofMillis(250));
    assertThat(CheckerOptions.parse("--grace", "30s").grace()).isEqualTo(Duration.ofSeconds(30));
    assertThat(CheckerOptions.parse("--grace", "5m").grace()).isEqualTo(Duration.ofMinutes(5));
    assertThat(CheckerOptions.parse("--grace", "1h").grace()).isEqualTo(Duration.ofHours(1));
    assertThat(CheckerOptions.parse("--grace", "7").grace()).isEqualTo(Duration.ofSeconds(7));
  }

  @Test
  void aSettleTimeoutOfZeroIsHowARunSaysItIsLookingAtAWorkingSystem() {
    assertThat(CheckerOptions.parse("--settle-timeout", "0").requiresSettled()).isFalse();
    assertThat(CheckerOptions.parse("--settle-timeout", "30s").requiresSettled()).isTrue();
  }

  @Test
  void readsTheOneEventToCheck() {
    UUID eventId = UUID.fromString("0192f000-0000-7000-8000-00000000e001");

    assertThat(CheckerOptions.parse("--event", eventId.toString()).eventId()).isEqualTo(eventId);
  }

  @Test
  void refusesAFlagItDoesNotKnow() {
    assertThatThrownBy(() -> CheckerOptions.parse("--postgres-hosts", "db"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unknown flag: --postgres-hosts");
  }

  @Test
  void refusesSomethingThatIsNotAFlagAtAll() {
    assertThatThrownBy(() -> CheckerOptions.parse("check", "everything"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a flag: check");
  }

  @Test
  void refusesAFlagWithNoValue() {
    assertThatThrownBy(() -> CheckerOptions.parse("--grace"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("--grace needs a value");
  }

  @Test
  void refusesValuesThatDoNotParse() {
    assertThatThrownBy(() -> CheckerOptions.parse("--grace", "a while"))
        .hasMessageContaining("--grace is not a duration");
    assertThatThrownBy(() -> CheckerOptions.parse("--postgres-port", "nope"))
        .hasMessageContaining("--postgres-port is not a number");
    assertThatThrownBy(() -> CheckerOptions.parse("--redis-port", "70000"))
        .hasMessageContaining("--redis-port is not a port");
    assertThatThrownBy(() -> CheckerOptions.parse("--examples", "-1"))
        .hasMessageContaining("--examples cannot be negative");
    assertThatThrownBy(() -> CheckerOptions.parse("--event", "the-midnight-set"))
        .hasMessageContaining("--event is not a uuid");
  }

  @Test
  void recognisesEveryWayOfAskingForTheUsage() {
    assertThat(CheckerOptions.isHelp("--help")).isTrue();
    assertThat(CheckerOptions.isHelp("-h")).isTrue();
    assertThat(CheckerOptions.isHelp("help")).isTrue();
    assertThat(CheckerOptions.isHelp("--grace", "5s", "--help")).isTrue();
    assertThat(CheckerOptions.isHelp("--grace", "5s")).isFalse();
  }

  @Test
  void theUsageTextNamesEveryFlagItAccepts() {
    String usage = CheckerOptions.usage();

    for (String flag :
        new String[] {
          "--postgres-host",
          "--postgres-port",
          "--user",
          "--password",
          "--inventory-db",
          "--order-db",
          "--payment-db",
          "--redis-host",
          "--redis-port",
          "--settle-timeout",
          "--grace",
          "--examples",
          "--event"
        }) {
      assertThat(usage).contains(flag);
    }
  }
}
