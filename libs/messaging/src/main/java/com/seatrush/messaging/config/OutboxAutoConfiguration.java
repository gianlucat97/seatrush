package com.seatrush.messaging.config;

import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.consumer.IdempotentConsumer;
import com.seatrush.messaging.consumer.JdbcProcessedMessages;
import com.seatrush.messaging.consumer.ProcessedMessages;
import com.seatrush.messaging.outbox.JdbcOutboxStore;
import com.seatrush.messaging.outbox.KafkaMessagePublisher;
import com.seatrush.messaging.outbox.MessagePublisher;
import com.seatrush.messaging.outbox.Outbox;
import com.seatrush.messaging.outbox.OutboxRelay;
import com.seatrush.messaging.outbox.OutboxStore;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Contributes the Postgres outbox and the idempotent consumer to a service that has a database.
 *
 * <p>The three services that run a step of the checkout SAGA need the same four pieces, and none of
 * them is a decision any of the three should take on its own: the writer that joins the business
 * transaction, the relay that publishes what it wrote, the table of processed messages and the
 * wrapper that uses it. The services without a database get none of this, which is why every bean
 * here is conditional on a {@link JdbcClient} rather than on a property.
 *
 * <p>{@link OutboxProperties} is enabled explicitly: services scan their own configuration
 * properties, and the ones a library brings with it are not under their package.
 *
 * <p>The relay is in a nested configuration of its own, because Kafka and the database arrive
 * separately. A service can write to the outbox without being a producer, for example when another
 * deployment relays for it, and it then gets the writer without the publisher instead of failing to
 * start.
 */
@AutoConfiguration(
    after = MessagingAutoConfiguration.class,
    afterName = {
      "org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration",
      "org.springframework.boot.transaction.autoconfigure.TransactionAutoConfiguration",
      "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
    })
@ConditionalOnClass({JdbcClient.class, TransactionTemplate.class})
@ConditionalOnBean({JdbcClient.class, TransactionTemplate.class})
@EnableConfigurationProperties(OutboxProperties.class)
public class OutboxAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public OutboxStore outboxStore(JdbcClient jdbc, Clock clock) {
    return new JdbcOutboxStore(jdbc, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public Outbox outbox(OutboxStore store, MessageCodec codec) {
    return new Outbox(store, codec);
  }

  @Bean
  @ConditionalOnMissingBean
  public ProcessedMessages processedMessages(JdbcClient jdbc, Clock clock) {
    return new JdbcProcessedMessages(jdbc, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public IdempotentConsumer idempotentConsumer(
      TransactionTemplate transactions, ProcessedMessages processed, MeterRegistry meters) {
    return new IdempotentConsumer(transactions, processed, meters);
  }

  /** The publishing half, which needs a broker as well as a database. */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnClass(KafkaTemplate.class)
  @ConditionalOnBean(KafkaTemplate.class)
  static class RelayConfiguration {

    @Bean
    @ConditionalOnMissingBean
    MessagePublisher messagePublisher(KafkaTemplate<String, String> kafka) {
      return new KafkaMessagePublisher(kafka);
    }

    @Bean
    @ConditionalOnMissingBean
    OutboxRelay outboxRelay(
        TransactionTemplate transactions,
        OutboxStore store,
        MessagePublisher publisher,
        OutboxProperties properties,
        Clock clock,
        MeterRegistry meters) {
      return new OutboxRelay(transactions, store, publisher, properties, clock, meters);
    }
  }
}
