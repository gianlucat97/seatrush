package com.seatrush.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.config.CommonAutoConfiguration;
import com.seatrush.messaging.consumer.IdempotentConsumer;
import com.seatrush.messaging.consumer.JdbcProcessedMessages;
import com.seatrush.messaging.consumer.ProcessedMessages;
import com.seatrush.messaging.outbox.JdbcOutboxStore;
import com.seatrush.messaging.outbox.KafkaMessagePublisher;
import com.seatrush.messaging.outbox.MessagePublisher;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.messaging.outbox.OutboxEntry;
import com.seatrush.messaging.outbox.OutboxRelay;
import com.seatrush.messaging.outbox.OutboxStore;
import com.seatrush.testsupport.transaction.DirectTransactionManager;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * What a service gets by depending on this module, and what it does not.
 *
 * <p>The distinction the tests are about: the outbox is for the services that own a database, and
 * nothing of it may appear in the ones that only produce or consume messages.
 */
class OutboxAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommonAutoConfiguration.class,
                  MessagingAutoConfiguration.class,
                  OutboxAutoConfiguration.class))
          .withUserConfiguration(MetricsConfiguration.class);

  @Test
  void contributesTheWholeOutboxToAServiceWithADatabaseAndABroker() {
    runner
        .withUserConfiguration(DatabaseConfiguration.class, BrokerConfiguration.class)
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(Outbox.class)
                  .hasSingleBean(OutboxRelay.class)
                  .hasSingleBean(IdempotentConsumer.class);
              assertThat(context.getBean(OutboxStore.class)).isInstanceOf(JdbcOutboxStore.class);
              assertThat(context.getBean(ProcessedMessages.class))
                  .isInstanceOf(JdbcProcessedMessages.class);
              assertThat(context.getBean(MessagePublisher.class))
                  .isInstanceOf(KafkaMessagePublisher.class);
            });
  }

  @Test
  void contributesNothingToAServiceWithoutADatabase() {
    runner
        .withUserConfiguration(BrokerConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .doesNotHaveBean(Outbox.class)
                    .doesNotHaveBean(OutboxStore.class)
                    .doesNotHaveBean(OutboxRelay.class)
                    .doesNotHaveBean(ProcessedMessages.class)
                    .doesNotHaveBean(IdempotentConsumer.class));
  }

  /**
   * A service can own the writing half without being the one that publishes it, so the missing
   * broker costs it the relay and not the ability to record a message at all.
   */
  @Test
  void contributesTheWriterWithoutTheRelayToAServiceWithoutABroker() {
    runner
        .withUserConfiguration(DatabaseConfiguration.class)
        .run(
            context ->
                assertThat(context)
                    .hasSingleBean(Outbox.class)
                    .hasSingleBean(IdempotentConsumer.class)
                    .doesNotHaveBean(MessagePublisher.class)
                    .doesNotHaveBean(OutboxRelay.class));
  }

  /** The defaults of README section 22, so a service need not repeat them. */
  @Test
  void relaysEveryHundredMillisecondsInBatchesOfFiveHundredUnlessToldOtherwise() {
    runner
        .withUserConfiguration(DatabaseConfiguration.class, BrokerConfiguration.class)
        .run(
            context -> {
              OutboxProperties properties = context.getBean(OutboxProperties.class);
              assertThat(properties.pollInterval()).isEqualTo(Duration.ofMillis(100));
              assertThat(properties.batchSize()).isEqualTo(500);
            });
  }

  @Test
  void takesTheRelaySettingsOfTheServiceWhenItHasThem() {
    runner
        .withUserConfiguration(DatabaseConfiguration.class, BrokerConfiguration.class)
        .withPropertyValues("seatrush.outbox.poll-interval=1s", "seatrush.outbox.batch-size=10")
        .run(
            context -> {
              OutboxProperties properties = context.getBean(OutboxProperties.class);
              assertThat(properties.pollInterval()).isEqualTo(Duration.ofSeconds(1));
              assertThat(properties.batchSize()).isEqualTo(10);
            });
  }

  @Test
  void stepsAsideWhenTheServiceDeclaresItsOwnStore() {
    runner
        .withUserConfiguration(
            DatabaseConfiguration.class, BrokerConfiguration.class, OwnStoreConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(OutboxStore.class);
              assertThat(context.getBean(OutboxStore.class))
                  .isSameAs(context.getBean(OwnStoreConfiguration.class).store);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class MetricsConfiguration {

    @Bean
    MeterRegistry meterRegistry() {
      return new SimpleMeterRegistry();
    }
  }

  /** What a service with {@code spring-boot-starter-data-jdbc} already has. */
  @Configuration(proxyBeanMethods = false)
  static class DatabaseConfiguration {

    @Bean
    JdbcClient jdbcClient() {
      return JdbcClient.create(new JdbcTemplate());
    }

    @Bean
    TransactionTemplate transactionTemplate() {
      return new TransactionTemplate(new DirectTransactionManager());
    }
  }

  /** What a service with {@code spring-boot-starter-kafka} already has. */
  @Configuration(proxyBeanMethods = false)
  static class BrokerConfiguration {

    @Bean
    KafkaTemplate<String, String> kafkaTemplate() {
      return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(Map.of()));
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnStoreConfiguration {

    private final OutboxStore store =
        new OutboxStore() {

          @Override
          public void append(String aggregateKey, String topic, String message) {}

          @Override
          public List<OutboxEntry> claim(int batchSize) {
            return List.of();
          }

          @Override
          public void markPublished(List<Long> ids) {}
        };

    @Bean
    OutboxStore outboxStore() {
      return store;
    }
  }
}
