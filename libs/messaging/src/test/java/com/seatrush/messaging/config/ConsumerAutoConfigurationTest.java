package com.seatrush.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.config.CommonAutoConfiguration;
import com.seatrush.messaging.Topics;
import java.time.Duration;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;

/**
 * What a consumer gets by depending on this module, and where a record it cannot handle goes.
 *
 * <p>Whether the policy actually retries and actually parks is asserted against a real broker in
 * {@code MessageConsumerIT}; here it is the wiring and the destination.
 */
class ConsumerAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommonAutoConfiguration.class,
                  MessagingAutoConfiguration.class,
                  ConsumerAutoConfiguration.class));

  @Test
  void contributesTheErrorPolicyToAServiceWithABroker() {
    runner
        .withUserConfiguration(BrokerConfiguration.class)
        .run(
            context -> {
              assertThat(context)
                  .hasSingleBean(DeadLetterPublishingRecoverer.class)
                  .hasSingleBean(CommonErrorHandler.class);
              assertThat(context.getBean(CommonErrorHandler.class))
                  .isInstanceOf(DefaultErrorHandler.class);
            });
  }

  /** A service that consumes nothing and produces nothing has no broker and needs no policy. */
  @Test
  void contributesNothingToAServiceWithoutABroker() {
    runner.run(
        context ->
            assertThat(context)
                .doesNotHaveBean(CommonErrorHandler.class)
                .doesNotHaveBean(DeadLetterPublishingRecoverer.class));
  }

  /** The defaults of README section 22, so a service need not repeat them. */
  @Test
  void retriesThreeTimesWithAWideningPauseUnlessToldOtherwise() {
    runner
        .withUserConfiguration(BrokerConfiguration.class)
        .run(
            context -> {
              ConsumerProperties properties = context.getBean(ConsumerProperties.class);
              assertThat(properties.retries()).isEqualTo(3);
              assertThat(properties.retryBackoff()).isEqualTo(Duration.ofMillis(500));
              assertThat(properties.retryMaxBackoff()).isEqualTo(Duration.ofSeconds(5));
            });
  }

  @Test
  void takesTheRetrySettingsOfTheServiceWhenItHasThem() {
    runner
        .withUserConfiguration(BrokerConfiguration.class)
        .withPropertyValues(
            "seatrush.consumer.retries=1",
            "seatrush.consumer.retry-backoff=10ms",
            "seatrush.consumer.retry-max-backoff=20ms")
        .run(
            context -> {
              ConsumerProperties properties = context.getBean(ConsumerProperties.class);
              assertThat(properties.retries()).isEqualTo(1);
              assertThat(properties.retryBackoff()).isEqualTo(Duration.ofMillis(10));
              assertThat(properties.retryMaxBackoff()).isEqualTo(Duration.ofMillis(20));
            });
  }

  @Test
  void stepsAsideWhenTheServiceDeclaresItsOwnErrorHandler() {
    runner
        .withUserConfiguration(BrokerConfiguration.class, OwnHandlerConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(CommonErrorHandler.class);
              assertThat(context.getBean(CommonErrorHandler.class))
                  .isSameAs(context.getBean(OwnHandlerConfiguration.class).handler);
            });
  }

  @Test
  void parksARecordInTheDeadLetterTopicOfItsOwnTopic() {
    TopicPartition destination =
        ConsumerAutoConfiguration.deadLetterTopicOf(
            recordOf(Topics.PAYMENT_COMMANDS), new IllegalStateException("boom"));

    assertThat(destination.topic()).isEqualTo(Topics.PAYMENT_COMMANDS + Topics.DLQ_SUFFIX);
    assertThat(destination.topic()).isEqualTo(Topics.dlqOf(Topics.PAYMENT_COMMANDS));
    assertThat(destination.partition())
        .as("the key decides, so one order stays in order in the DLQ too")
        .isEqualTo(-1);
  }

  /** A poison message that fails again has nowhere further to go and must stay where it is. */
  @Test
  void neverParksARecordThatIsAlreadyInADeadLetterTopic() {
    assertThat(
            ConsumerAutoConfiguration.deadLetterTopicOf(
                recordOf(Topics.dlqOf(Topics.PAYMENT_COMMANDS)), new IllegalStateException("boom")))
        .isNull();
  }

  private static ConsumerRecord<String, String> recordOf(String topic) {
    return new ConsumerRecord<>(topic, 7, 0L, "key", "value");
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
  static class OwnHandlerConfiguration {

    private final CommonErrorHandler handler = new DefaultErrorHandler();

    @Bean
    CommonErrorHandler messageErrorHandler() {
      return handler;
    }
  }
}
