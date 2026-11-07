package com.seatrush.invariants;

import java.io.PrintStream;

/**
 * The entry point of the invariant checker (README section 17).
 *
 * <p>It reads the three databases and the Redis of a running deployment, checks the invariants of
 * README section 4 that a finished run can still be asked about, prints a report and exits with a
 * code that says whether the run can be trusted. That exit code is the whole contract: it runs
 * after every load test and in CI, and the thing reading it there is a shell.
 *
 * <p>Zero when every check ran and held. One when an invariant is broken, which is the result this
 * tool exists to produce. Two when the check could not be made at all: a store that could not be
 * reached, a flag that does not parse, or a system that never stopped working, because an answer
 * nobody has is not a guarantee and a checker that reported it as one would be worse than no
 * checker.
 *
 * <p>Three things are deliberately not here. It never writes, so a run can be checked twice and a
 * failed run can be looked at by hand afterwards. It calls no service, so what it reports is the
 * state of the stores rather than what a service says about it. And it has no idea which load test
 * produced the state it is reading, so the same command checks a benchmark, a demo and a cluster
 * somebody has been clicking around in.
 */
public final class InvariantChecker {

  private InvariantChecker() {}

  public static void main(String[] arguments) {
    System.exit(run(arguments, System.out, System.err));
  }

  /**
   * One run, with its streams handed in.
   *
   * <p>Separate from {@link #main(String[])} and returning the code rather than exiting with it, so
   * that a test can run the whole tool, read the report it printed and assert on the verdict. A
   * {@code System.exit} inside here would take the test JVM with it.
   */
  static int run(String[] arguments, PrintStream out, PrintStream error) {
    if (CheckerOptions.isHelp(arguments)) {
      out.print(CheckerOptions.usage());
      return 0;
    }

    CheckerOptions options;
    try {
      options = CheckerOptions.parse(arguments);
    } catch (IllegalArgumentException e) {
      error.println(e.getMessage());
      error.println();
      error.print(CheckerOptions.usage());
      return 2;
    }

    try (SnapshotReader reader = SnapshotReader.open(options)) {
      SnapshotReader.Settling settling = reader.settle(error);
      Snapshot snapshot = reader.read();
      Findings findings = new Findings();
      Invariants.checkAll(snapshot, options, settling.settled(), findings);
      new Report(
              findings,
              options,
              new Report.RunContext(snapshot.readAt().toString(), settling.description()))
          .printTo(out);
      // Only a run that asked for a settled system is certified. A skipped check is a failure
      // there and an expected answer in the other mode, where the caller said it was looking at a
      // system that is still working.
      return findings.exitCode(options.requiresSettled());
    } catch (RuntimeException e) {
      error.println("the invariant checker could not read the system: " + e.getMessage());
      return 2;
    }
  }
}
