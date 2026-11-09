package com.seatrush.testsupport.docs;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Publishes the OpenAPI document of one service under {@code docs/api/}, or checks the committed
 * one against it.
 *
 * <p>Six services each have an integration test that asks their own running context for {@code
 * /v3/api-docs.yaml}, and the half of that test which is the same for all six is here. Without a
 * flag it compares, so a document that has drifted from the controllers fails the build and says
 * how to put it right; with {@code -Dseatrush.api.write=true}, which is what {@code make api-docs}
 * passes, it writes instead. One generator and one check, as with the message schemas of README
 * section 17.3: two would be two descriptions of one API to keep in step.
 *
 * <p>This lives in test support rather than in a service because the rule it enforces is about the
 * directory and not about any one service. A copy per service would be six places for the write
 * flag, the line endings and the version check to disagree.
 */
public final class OpenApiDocs {

  /** Set by {@code make api-docs} to write the documents instead of comparing them. */
  public static final String WRITE = "seatrush.api.write";

  /**
   * The version the build is producing, passed to the integration tests by Failsafe.
   *
   * <p>It is how six hand-written {@code info.version} strings are kept from going stale one
   * release at a time, which is a thing that would happen silently and inside a published contract.
   */
  private static final String PROJECT_VERSION = "seatrush.project.version";

  /** The directory of README section 20, relative to the repository root. */
  private static final String DIRECTORY = "docs/api";

  /** A key of the top-level {@code info} block, which is the only block looked into here. */
  private static final Pattern INFO_BLOCK =
      Pattern.compile("^info:$(.*?)^\\S", Pattern.MULTILINE | Pattern.DOTALL);

  private static final Pattern VERSION =
      Pattern.compile("^  version: \"?([^\"\\n]+?)\"?$", Pattern.MULTILINE);

  private OpenApiDocs() {}

  /**
   * Checks the document a service serves and either writes it or compares it to the committed one.
   *
   * <p>The version check runs either way, because writing a document that calls itself by the wrong
   * version would commit the mistake rather than report it.
   *
   * @param service the service name, which is the file name under {@code docs/api/}
   * @param document the body of {@code /v3/api-docs.yaml} as that service served it
   * @throws AssertionError if the version is wrong, or if the committed document differs
   */
  public static void publish(String service, String document) {
    String yaml = normalise(document);
    assertVersionIsTheOneBeingBuilt(service, yaml);

    Path file = directory().resolve(service + ".yaml");
    if (Boolean.getBoolean(WRITE)) {
      write(file, yaml);
      return;
    }
    if (!Files.isRegularFile(file)) {
      throw new AssertionError(
          "%s is not published yet. Generate it with make api-docs".formatted(file));
    }
    String committed = read(file);
    if (!committed.equals(yaml)) {
      throw new AssertionError(
          """
          %s has drifted from the endpoints of %s. Regenerate it with make api-docs.

          The committed document is %d characters and the one this service serves is %d. \
          First difference at character %d:
            committed: %s
            served:    %s\
          """
              .formatted(
                  file,
                  service,
                  committed.length(),
                  yaml.length(),
                  firstDifference(committed, yaml),
                  around(committed, firstDifference(committed, yaml)),
                  around(yaml, firstDifference(committed, yaml))));
    }
  }

  /**
   * Line endings forced to LF, with exactly one at the end.
   *
   * <p>The same lesson the message schemas learned: {@code .gitattributes} checks these files out
   * with LF everywhere and this comparison is a string comparison, so a document carrying platform
   * line endings would pass in CI and fail on a Windows checkout for every service at once. That is
   * a test that reports the operating system rather than the API.
   */
  private static String normalise(String document) {
    return document.replace("\r\n", "\n").stripTrailing() + "\n";
  }

  /**
   * The {@code info.version} of the document against the version Maven is building.
   *
   * <p>Snapshot or not makes no difference to the comparison: a document published from {@code
   * 0.1.0-SNAPSHOT} describes the API of the release it is heading for, and {@code info.version} is
   * the version of the API rather than of the jar that serves it.
   */
  private static void assertVersionIsTheOneBeingBuilt(String service, String yaml) {
    String expected = System.getProperty(PROJECT_VERSION, "").replace("-SNAPSHOT", "");
    if (expected.isEmpty()) {
      // Failsafe passes it; a run from an IDE may not, and a missing property should not turn
      // into a failure about a version nobody set.
      return;
    }
    Matcher info = INFO_BLOCK.matcher(yaml);
    if (!info.find()) {
      throw new AssertionError("the document of " + service + " has no info block");
    }
    Matcher version = VERSION.matcher(info.group(1));
    if (!version.find()) {
      throw new AssertionError("the info block of " + service + " declares no version");
    }
    if (!expected.equals(version.group(1))) {
      throw new AssertionError(
          ("%s publishes its API as version %s and this build is %s. The version is the one in the"
                  + " @OpenAPIDefinition of its application class")
              .formatted(service, version.group(1), expected));
    }
  }

  /** {@code docs/api/}, found by walking up rather than by guessing the working directory. */
  private static Path directory() {
    Path path = Path.of("").toAbsolutePath();
    while (path != null && !Files.isDirectory(path.resolve("libs/test-support"))) {
      path = path.getParent();
    }
    if (path == null) {
      throw new UncheckedIOException(
          new IOException(
              "the repository root was not found from " + Path.of("").toAbsolutePath()));
    }
    return path.resolve(DIRECTORY);
  }

  private static int firstDifference(String committed, String served) {
    int limit = Math.min(committed.length(), served.length());
    for (int index = 0; index < limit; index++) {
      if (committed.charAt(index) != served.charAt(index)) {
        return index;
      }
    }
    return limit;
  }

  /** Sixty characters around an offset, on one line, so a diff is readable in a build log. */
  private static String around(String document, int offset) {
    int from = Math.max(0, offset - 20);
    int to = Math.min(document.length(), offset + 40);
    return document.substring(from, to).replace("\n", "\\n");
  }

  private static String read(Path file) {
    try {
      return normalise(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException("could not read " + file, e);
    }
  }

  private static void write(Path file, String yaml) {
    try {
      Files.createDirectories(file.getParent());
      Files.writeString(file, yaml, StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException("could not write " + file, e);
    }
  }
}
