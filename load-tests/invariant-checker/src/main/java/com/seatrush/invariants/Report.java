package com.seatrush.invariants;

import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * The findings as something a person reads.
 *
 * <p>The verdict table comes first and the detail after it, because the first question anybody asks
 * of a benchmark run is whether it was clean. A report that opened with four thousand oversold
 * seats would bury its own answer.
 *
 * <p>Nothing here is coloured and nothing is aligned with tabs. This output is pasted into {@code
 * docs/benchmarks/<date>/} and read in a terminal, a pull request and a plain text file, and the
 * three agree only about spaces.
 */
final class Report {

  /** Width of the label column, which the longest label of {@link Invariant} decides. */
  private static final int LABEL_WIDTH = 7;

  private static final int VERDICT_WIDTH = 9;

  private final Findings findings;
  private final CheckerOptions options;
  private final RunContext context;

  Report(Findings findings, CheckerOptions options, RunContext context) {
    this.findings = findings;
    this.options = options;
    this.context = context;
  }

  /**
   * What the run was, as the head of the report.
   *
   * @param readAt when the reading started, which every deadline was judged against
   * @param state one line about whether anything was still in flight
   */
  record RunContext(String readAt, String state) {}

  void printTo(PrintStream out) {
    out.println("SeatRush invariant checker (README section 17)");
    out.println();
    out.printf("  read at   %s%n", context.readAt());
    out.printf(
        "  postgres  %s:%d (%s, %s, %s)%n",
        options.postgresHost(),
        options.postgresPort(),
        options.inventoryDatabase(),
        options.orderDatabase(),
        options.paymentDatabase());
    out.printf("  redis     %s:%d%n", options.redisHost(), options.redisPort());
    out.printf("  state     %s%n", context.state());
    if (options.eventId() != null) {
      out.printf("  event     %s%n", options.eventId());
    }
    out.println();

    for (Invariant invariant : Invariant.values()) {
      out.printf(
          "  %-" + LABEL_WIDTH + "s %-" + VERDICT_WIDTH + "s %s%n",
          invariant.label(),
          findings.verdictOf(invariant),
          invariant.title());
      for (String note : findings.notesOf(invariant)) {
        out.printf("  %-" + LABEL_WIDTH + "s %-" + VERDICT_WIDTH + "s %s%n", "", "", note);
      }
    }

    printDetail(out);
    printTotals(out);
    out.println();
    out.println(verdictLine());
  }

  /**
   * The offending rows of every check that failed, and the reason of every one that was skipped.
   */
  private void printDetail(PrintStream out) {
    for (Invariant invariant : Invariant.values()) {
      switch (findings.verdictOf(invariant)) {
        case VIOLATED -> printViolations(out, invariant);
        case SKIPPED ->
            out.printf(
                "%n  %s was skipped: %s%n", invariant.label(), findings.skipReasonOf(invariant));
        case HELD -> {
          // Nothing to add: the table above already said it held, and the notes said over what.
        }
      }
    }
  }

  private void printViolations(PrintStream out, Invariant invariant) {
    List<String> violations = findings.violationsOf(invariant);
    out.printf(
        "%n  %s is violated, %d %s%n",
        invariant.label(), violations.size(), violations.size() == 1 ? "time" : "times");
    int shown = Math.min(options.examples(), violations.size());
    for (String violation : violations.subList(0, shown)) {
      out.printf("    %s%n", violation);
    }
    if (shown < violations.size()) {
      out.printf("    and %d more%n", violations.size() - shown);
    }
  }

  private void printTotals(PrintStream out) {
    List<Map.Entry<String, String>> totals = findings.orderedTotals();
    if (totals.isEmpty()) {
      return;
    }
    int width = totals.stream().mapToInt(total -> total.getKey().length()).max().orElse(0);
    out.println();
    out.println("  Totals");
    for (Map.Entry<String, String> total : totals) {
      out.printf("    %-" + width + "s  %s%n", total.getKey(), total.getValue());
    }
  }

  /**
   * The one line a reader takes away, and the one a grep is written against.
   *
   * <p>It names the counts rather than only the word, because a run with one violated invariant and
   * a run with five are different incidents and the summary is what gets quoted.
   */
  String verdictLine() {
    long broken = count(Findings.Verdict.VIOLATED);
    long skipped = count(Findings.Verdict.SKIPPED);
    int total = Invariant.values().length;
    if (broken > 0) {
      return "VIOLATED: %d of %d checks failed with %d findings, %d skipped"
          .formatted(broken, total, findings.violationCount(), skipped);
    }
    if (skipped > 0 && options.requiresSettled()) {
      return "INCONCLUSIVE: %d of %d checks held and %d could not be made"
          .formatted(total - skipped, total, skipped);
    }
    if (skipped > 0) {
      return "OK: %d of %d checks held, %d skipped on a system still in flight"
          .formatted(total - skipped, total, skipped);
    }
    return "OK: all %d checks held".formatted(total);
  }

  private long count(Findings.Verdict verdict) {
    return Arrays.stream(Invariant.values())
        .filter(invariant -> findings.verdictOf(invariant) == verdict)
        .count();
  }
}
