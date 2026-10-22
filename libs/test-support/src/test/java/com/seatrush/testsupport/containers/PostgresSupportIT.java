package com.seatrush.testsupport.containers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

@EnabledIfDockerAvailable
class PostgresSupportIT {

  private static final String OWNED_DATABASE = "owned_by_a_test";

  @AfterEach
  void removeTheDatabaseTheTestCreated() {
    PostgresSupport.dropDatabase(OWNED_DATABASE);
  }

  @Test
  void startsThePostgresVersionTheStackTargets() {
    assertThat(queryOneValue(PostgresSupport.jdbcUrl(), "SHOW server_version")).startsWith("17.");
  }

  @Test
  void theDefaultDatabaseIsReachableOverJdbc() {
    assertThat(queryOneValue(PostgresSupport.jdbcUrl(), "SELECT current_database()"))
        .isEqualTo(PostgresSupport.DEFAULT_DATABASE);
  }

  @Test
  void exposesTheMappedPortInTheJdbcUrl() {
    assertThat(PostgresSupport.port()).isPositive();
    assertThat(PostgresSupport.jdbcUrl())
        .isEqualTo(
            "jdbc:postgresql://"
                + PostgresSupport.host()
                + ":"
                + PostgresSupport.port()
                + "/"
                + PostgresSupport.DEFAULT_DATABASE);
  }

  @Test
  void everyCallerGetsTheSameContainer() {
    assertThat(PostgresSupport.container()).isSameAs(PostgresSupport.container());
    assertThat(PostgresSupport.container().isRunning()).isTrue();
  }

  @Test
  void createsAnEmptyDatabaseOwnedByTheServiceRole() {
    String url = PostgresSupport.createDatabase(OWNED_DATABASE);

    assertThat(url).isEqualTo(PostgresSupport.jdbcUrlOf(OWNED_DATABASE));
    assertThat(queryOneValue(url, "SELECT current_database()")).isEqualTo(OWNED_DATABASE);
    assertThat(queryOneValue(url, "SELECT current_user")).isEqualTo(PostgresSupport.USERNAME);
    assertThat(
            queryOneValue(
                url,
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public'"))
        .isEqualTo("0");
  }

  @Test
  void aDatabaseIsIsolatedFromTheDefaultOne() {
    String url = PostgresSupport.createDatabase(OWNED_DATABASE);
    execute(url, "CREATE TABLE seat_sale (seat_id text PRIMARY KEY)");

    assertThat(
            queryOneValue(
                PostgresSupport.jdbcUrl(),
                "SELECT count(*) FROM information_schema.tables WHERE table_name = 'seat_sale'"))
        .as("the table the other database owns is not visible here")
        .isEqualTo("0");
  }

  @Test
  void creatingTheSameDatabaseTwiceIsReported() {
    PostgresSupport.createDatabase(OWNED_DATABASE);

    assertThatThrownBy(() -> PostgresSupport.createDatabase(OWNED_DATABASE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("already exists");
  }

  @Test
  void droppingADatabaseThatIsNotThereIsNotAnError() {
    PostgresSupport.dropDatabase(OWNED_DATABASE);
    PostgresSupport.dropDatabase(OWNED_DATABASE);
  }

  @Test
  void rejectsADatabaseNameItWouldHaveToInterpolate() {
    assertThatThrownBy(() -> PostgresSupport.createDatabase("drop table seat_sale; --"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("database name must match");
    assertThatThrownBy(() -> PostgresSupport.jdbcUrlOf("Inventory"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> PostgresSupport.jdbcUrlOf(null))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void registersTheSpringDatasourceProperties() {
    RecordingPropertyRegistry registry = new RecordingPropertyRegistry();

    PostgresSupport.registerProperties(registry);

    assertThat(registry.names())
        .containsExactly(
            "spring.datasource.url", "spring.datasource.username", "spring.datasource.password");
    assertThat(registry.valueOf("spring.datasource.url")).isEqualTo(PostgresSupport.jdbcUrl());
    assertThat(registry.valueOf("spring.datasource.username")).isEqualTo(PostgresSupport.USERNAME);
    assertThat(registry.valueOf("spring.datasource.password")).isEqualTo(PostgresSupport.PASSWORD);
  }

  @Test
  void registersTheDatasourcePropertiesOfOneDatabase() {
    RecordingPropertyRegistry registry = new RecordingPropertyRegistry();

    PostgresSupport.registerProperties(registry, "seatrush_inventory");

    assertThat(registry.valueOf("spring.datasource.url"))
        .isEqualTo(PostgresSupport.jdbcUrlOf("seatrush_inventory"));
  }

  private static String queryOneValue(String jdbcUrl, String sql) {
    try (Connection connection = connect(jdbcUrl);
        Statement statement = connection.createStatement();
        ResultSet rows = statement.executeQuery(sql)) {
      assertThat(rows.next()).as("query returned no row: %s", sql).isTrue();
      return rows.getString(1);
    } catch (SQLException e) {
      throw new AssertionError("query failed: " + sql, e);
    }
  }

  private static void execute(String jdbcUrl, String sql) {
    try (Connection connection = connect(jdbcUrl);
        Statement statement = connection.createStatement()) {
      statement.execute(sql);
    } catch (SQLException e) {
      throw new AssertionError("statement failed: " + sql, e);
    }
  }

  private static Connection connect(String jdbcUrl) throws SQLException {
    return DriverManager.getConnection(jdbcUrl, PostgresSupport.USERNAME, PostgresSupport.PASSWORD);
  }
}
