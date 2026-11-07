package com.seatrush.invariants;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What the checks found, as they find it.
 *
 * <p>Every violation is kept, and only the printing is capped: a run that oversold four thousand
 * seats has to be able to say four thousand rather than "more than ten". The examples are the first
 * ones in the order the checks walk their input, which for every check here is a stable order, so
 * two runs over the same state print the same report.
 *
 * <p>A check that cannot be answered is skipped with the reason why, and a skip is not an OK. The
 * report prints it as its own verdict and {@link #exitCode(boolean)} refuses to certify a run that
 * has one, which is the whole difference between a checker and a checker that is always green.
 */
final class Findings {

  private final Map<Invariant, List<String>> violations = new EnumMap<>(Invariant.class);
  private final Map<Invariant, String> skipped = new EnumMap<>(Invariant.class);
  private final Map<Invariant, List<String>> notes = new EnumMap<>(Invariant.class);

  /** The figures of the run, in the order they were measured, for the foot of the report. */
  private final Map<String, String> totals = new LinkedHashMap<>();

  /** One thing that is wrong, named precisely enough to be looked up in the database. */
  void violation(Invariant invariant, String message, Object... arguments) {
    violations
        .computeIfAbsent(invariant, key -> new ArrayList<>())
        .add(arguments.length == 0 ? message : message.formatted(arguments));
  }

  /** One thing the check could not be answered about, and why. */
  void skip(Invariant invariant, String reason, Object... arguments) {
    skipped.put(invariant, arguments.length == 0 ? reason : reason.formatted(arguments));
  }

  /**
   * Something true and worth knowing that is not a violation.
   *
   * <p>A note is how a check says what it looked at. An invariant reported as holding over an empty
   * database looks exactly like one reported as holding over twenty thousand seats, and only one of
   * the two is evidence of anything.
   */
  void note(Invariant invariant, String message, Object... arguments) {
    notes
        .computeIfAbsent(invariant, key -> new ArrayList<>())
        .add(arguments.length == 0 ? message : message.formatted(arguments));
  }

  /** One figure of the run, printed in the summary at the end. */
  void total(String name, String value) {
    totals.put(name, value);
  }

  void total(String name, long value) {
    total(name, Long.toString(value));
  }

  List<String> violationsOf(Invariant invariant) {
    return List.copyOf(violations.getOrDefault(invariant, List.of()));
  }

  List<String> notesOf(Invariant invariant) {
    return List.copyOf(notes.getOrDefault(invariant, List.of()));
  }

  String skipReasonOf(Invariant invariant) {
    return skipped.get(invariant);
  }

  Map<String, String> totals() {
    return Map.copyOf(totals);
  }

  /** The figures in the order they were recorded, which the report prints them in. */
  List<Map.Entry<String, String>> orderedTotals() {
    return List.copyOf(totals.entrySet());
  }

  Verdict verdictOf(Invariant invariant) {
    if (!violations.getOrDefault(invariant, List.of()).isEmpty()) {
      return Verdict.VIOLATED;
    }
    return skipped.containsKey(invariant) ? Verdict.SKIPPED : Verdict.HELD;
  }

  boolean hasViolations() {
    return violations.values().stream().anyMatch(found -> !found.isEmpty());
  }

  long violationCount() {
    return violations.values().stream().mapToLong(List::size).sum();
  }

  boolean hasSkips() {
    return !skipped.isEmpty();
  }

  /**
   * The process exit code, which is the only part of this report a CI job reads.
   *
   * <p>One for a violation, since that is a broken guarantee and the reason the tool exists. Two
   * for a check that could not be made, because an answer nobody has is not a guarantee either, and
   * a checker that exited zero on it would quietly turn a gate into decoration. Zero only when
   * every check ran and every one of them held.
   *
   * @param certify whether the caller asked for a verdict on a finished run, in which case a
   *     skipped check is a failure. A deliberate look at a system that is still working asks for
   *     the opposite and takes the skips as expected.
   */
  int exitCode(boolean certify) {
    if (hasViolations()) {
      return 1;
    }
    return certify && hasSkips() ? 2 : 0;
  }

  /** How one check came out. */
  enum Verdict {
    HELD,
    VIOLATED,
    SKIPPED
  }
}
