package com.seatrush.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * What the report says, and the exit code that goes with it.
 *
 * <p>Both halves are a contract. The exit code is read by the shell that runs the tool after a load
 * test and by the CI job that gates on it, and the verdict line is what gets quoted into the
 * results of README section 18.3, so a run that was not fully checked has to say so in words as
 * well as in its exit status.
 */
class ReportTest {

  @Test
  void aCleanRunSaysSoInOneLineAndExitsZero() {
    Findings found = new Findings();
    found.note(Invariant.SEAT_SOLD_ONCE, "8143 sales over 8143 distinct seats");
    found.total("seats sold", 8143);

    String printed = print(found, settled());

    assertThat(found.exitCode(true)).isZero();
    assertThat(printed).contains("OK: all 7 checks held");
    assertThat(printed).contains("8143 sales over 8143 distinct seats");
    assertThat(printed).contains("seats sold  8143");
    assertThat(printed).doesNotContain("VIOLATED");
  }

  @Test
  void aViolationIsCountedInFullAndPrintedInPart() {
    Findings found = new Findings();
    for (int seat = 1; seat <= 25; seat++) {
      found.violation(Invariant.SEAT_SOLD_ONCE, "seat A-1-%d has 2 seat_sale rows", seat);
    }

    String printed = print(found, settled());

    assertThat(found.exitCode(true)).isEqualTo(1);
    assertThat(printed).contains("I1 is violated, 25 times");
    assertThat(printed).contains("seat A-1-1 has 2 seat_sale rows");
    assertThat(printed).contains("seat A-1-10 has 2 seat_sale rows");
    assertThat(printed).doesNotContain("seat A-1-11 has");
    assertThat(printed).contains("and 15 more");
    assertThat(printed).contains("VIOLATED: 1 of 7 checks failed with 25 findings, 0 skipped");
  }

  @Test
  void aCheckThatCouldNotBeMadeIsNotAPass() {
    Findings found = new Findings();
    found.skip(Invariant.TOTALS_RECONCILE, "412 checkouts were still in flight");

    String printed = print(found, settled());

    assertThat(found.exitCode(true)).isEqualTo(2);
    assertThat(printed).contains("totals was skipped: 412 checkouts were still in flight");
    assertThat(printed).contains("INCONCLUSIVE: 6 of 7 checks held and 1 could not be made");
  }

  @Test
  void aDeliberateLookAtAWorkingSystemIsAllowedToSkip() {
    Findings found = new Findings();
    found.skip(Invariant.TOTALS_RECONCILE, "the system was still working");

    String printed = print(found, inFlight());

    assertThat(found.exitCode(false)).isZero();
    assertThat(printed).contains("OK: 6 of 7 checks held, 1 skipped on a system still in flight");
  }

  @Test
  void theHeadOfTheReportSaysWhatWasRead() {
    String printed = print(new Findings(), settled());

    assertThat(printed).contains("read at   2026-11-07T12:00:00Z");
    assertThat(printed).contains("postgres  db.internal:6543 (inv, ord, pay)");
    assertThat(printed).contains("redis     cache.internal:6380");
    assertThat(printed).contains("state     settled");
  }

  private static String print(Findings found, CheckerOptions options) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    try (PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8)) {
      new Report(
              found,
              options,
              new Report.RunContext("2026-11-07T12:00:00Z", "settled: nothing is in flight"))
          .printTo(out);
    }
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private static CheckerOptions settled() {
    return options("60s");
  }

  private static CheckerOptions inFlight() {
    return options("0");
  }

  private static CheckerOptions options(String settleTimeout) {
    return CheckerOptions.parse(
        "--postgres-host", "db.internal",
        "--postgres-port", "6543",
        "--inventory-db", "inv",
        "--order-db", "ord",
        "--payment-db", "pay",
        "--redis-host", "cache.internal",
        "--redis-port", "6380",
        "--examples", "10",
        "--settle-timeout", settleTimeout);
  }
}
