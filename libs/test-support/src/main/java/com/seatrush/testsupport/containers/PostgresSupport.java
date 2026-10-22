package com.seatrush.testsupport.containers;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.regex.Pattern;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL container shared by every test in the JVM.
 *
 * <p>One server is started on first use and left running until the JVM exits. Isolation comes from
 * {@link #createDatabase(String)} rather than from a container per test: the system owns three
 * databases (inventory, order, payment), tests want them empty rather than new, and creating a
 * database costs milliseconds against the seconds a Postgres boot costs.
 */
public final class PostgresSupport {

  /** Owner of every database created here, and the role the services connect as. */
  public static final String USERNAME = "seatrush";

  public static final String PASSWORD = "seatrush";

  /** The database the container creates on boot, used when a test needs no isolation. */
  public static final String DEFAULT_DATABASE = "seatrush";

  // Interpolated into CREATE DATABASE, which takes no bind parameters.
  private static final Pattern VALID_DATABASE_NAME = Pattern.compile("[a-z][a-z0-9_]{0,62}");

  private PostgresSupport() {}

  /** The shared container, started on the first call. */
  public static PostgreSQLContainer container() {
    return Holder.CONTAINER;
  }

  public static String host() {
    return container().getHost();
  }

  /** The port on the host that forwards to the server; a fresh one on every JVM run. */
  public static int port() {
    return container().getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT);
  }

  /** JDBC url of {@link #DEFAULT_DATABASE}. */
  public static String jdbcUrl() {
    return jdbcUrlOf(DEFAULT_DATABASE);
  }

  /** JDBC url of a database on the shared server, whether or not it exists yet. */
  public static String jdbcUrlOf(String database) {
    return "jdbc:postgresql://" + host() + ":" + port() + "/" + requireValidName(database);
  }

  /** Points {@code spring.datasource.*} of a Spring test context at {@link #DEFAULT_DATABASE}. */
  public static void registerProperties(DynamicPropertyRegistry registry) {
    registerProperties(registry, DEFAULT_DATABASE);
  }

  /** Points {@code spring.datasource.*} of a Spring test context at one database. */
  public static void registerProperties(DynamicPropertyRegistry registry, String database) {
    String name = requireValidName(database);
    registry.add("spring.datasource.url", () -> jdbcUrlOf(name));
    registry.add("spring.datasource.username", () -> USERNAME);
    registry.add("spring.datasource.password", () -> PASSWORD);
  }

  /**
   * Creates an empty database owned by {@link #USERNAME} and returns its JDBC url.
   *
   * <p>Call it once per test class that owns a schema, so Flyway migrations of different services
   * never meet in the same database.
   *
   * @throws IllegalArgumentException if the name is not a lowercase identifier, since it is
   *     interpolated into the statement
   * @throws IllegalStateException if the database already exists or the statement fails
   */
  public static String createDatabase(String name) {
    String database = requireValidName(name);
    psql("CREATE DATABASE " + database + " OWNER " + USERNAME);
    return jdbcUrlOf(database);
  }

  /** Drops a database created by {@link #createDatabase(String)}, if it is still there. */
  public static void dropDatabase(String name) {
    psql("DROP DATABASE IF EXISTS " + requireValidName(name) + " WITH (FORCE)");
  }

  /**
   * Runs one statement with {@code psql} inside the container and returns its trimmed output.
   *
   * <p>Statements that set a database up go through the container rather than a JDBC connection:
   * {@code CREATE DATABASE} cannot run inside a transaction, and the pool a test is exercising
   * should not be the one that built its own fixture.
   *
   * @throws IllegalStateException if psql exits non-zero
   */
  public static String psql(String statement) {
    try {
      ExecResult result =
          container()
              .execInContainer(
                  "psql",
                  "--username",
                  USERNAME,
                  "--dbname",
                  DEFAULT_DATABASE,
                  "--command",
                  statement);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "psql failed on [%s] with exit code %d: %s"
                .formatted(statement, result.getExitCode(), result.getStderr().strip()));
      }
      return result.getStdout().strip();
    } catch (IOException e) {
      throw new UncheckedIOException("could not run psql in the container", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running psql", e);
    }
  }

  private static String requireValidName(String database) {
    if (database == null || !VALID_DATABASE_NAME.matcher(database).matches()) {
      throw new IllegalArgumentException(
          "database name must match " + VALID_DATABASE_NAME.pattern() + " but was: " + database);
    }
    return database;
  }

  /** Holds the container so that it starts on first access and only once, without locking. */
  private static final class Holder {

    private static final PostgreSQLContainer CONTAINER = start();

    private static PostgreSQLContainer start() {
      PostgreSQLContainer container =
          new PostgreSQLContainer(ContainerImages.POSTGRES)
              .withDatabaseName(DEFAULT_DATABASE)
              .withUsername(USERNAME)
              .withPassword(PASSWORD);
      container.start();
      return container;
    }
  }
}
