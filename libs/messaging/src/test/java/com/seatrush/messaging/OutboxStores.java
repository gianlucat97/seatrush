package com.seatrush.messaging;

import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.support.EncodedResource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * The database and the broker the outbox integration tests run against.
 *
 * <p>Built by hand, because this module is a library and has no application to start. What it has
 * to reproduce is a service's wiring: a pooled {@code DataSource} over a database carrying the
 * {@code outbox} and {@code processed_message} tables of README section 11.1, and a producer
 * configured as README section 10.3 requires.
 */
public final class OutboxStores {

  private OutboxStores() {}

  /** A pool over an empty database of its own, with the outbox tables already migrated. */
  public static HikariDataSource database(String name) {
    PostgresSupport.dropDatabase(name);
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(PostgresSupport.createDatabase(name));
    config.setUsername(PostgresSupport.USERNAME);
    config.setPassword(PostgresSupport.PASSWORD);
    // Room for the relays and the claims of the concurrency tests to be in flight together: a
    // pool smaller than the threads would make them queue instead of race.
    config.setMaximumPoolSize(16);
    HikariDataSource dataSource = new HikariDataSource(config);
    applySchema(dataSource);
    return dataSource;
  }

  /**
   * The producer of README section 10.3: nothing is published unless every in-sync replica has it,
   * and a retried send cannot become a second message.
   */
  public static KafkaTemplate<String, String> producer() {
    return new KafkaTemplate<>(
        new DefaultKafkaProducerFactory<>(
            Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
                KafkaSupport.bootstrapServers(),
                ProducerConfig.ACKS_CONFIG,
                "all",
                ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG,
                true,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
                StringSerializer.class)));
  }

  private static void applySchema(HikariDataSource dataSource) {
    EncodedResource script =
        new EncodedResource(
            new ClassPathResource("db/outbox-tables.sql"), StandardCharsets.UTF_8.name());
    try (Connection connection = dataSource.getConnection()) {
      ScriptUtils.executeSqlScript(connection, script);
    } catch (SQLException e) {
      throw new IllegalStateException("could not create the outbox tables", e);
    }
  }
}
