package com.seatrush.messaging.config;

import com.seatrush.messaging.MessagingException;
import com.seatrush.messaging.Topics;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * The consumer error policy of README sections 10.3 and 16: bounded retries with backoff, then the
 * dead letter topic.
 *
 * <p>Contributed once here rather than written in each service, because it is not a decision any
 * one consumer should take: a poison message has to end up somewhere an operator can find it, and a
 * service that configured its own could silently drop one instead.
 *
 * <p>The two halves answer two different failures. A handler that threw because a database was
 * unreachable is worth trying again, so it is retried a few times with a widening pause. A record
 * that cannot be decoded, or whose type nobody handles, will fail exactly the same way on the next
 * attempt, so it goes to the DLQ immediately: that is what classifying the whole {@link
 * MessagingException} family as not retryable buys.
 *
 * <p>A business refusal is never either of these. A declined payment and a lost seat race travel as
 * reply messages, so they never reach an error handler at all.
 */
@AutoConfiguration(
    after = MessagingAutoConfiguration.class,
    afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass({KafkaTemplate.class, DefaultErrorHandler.class})
@ConditionalOnBean(KafkaTemplate.class)
@EnableConfigurationProperties(ConsumerProperties.class)
public class ConsumerAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(ConsumerAutoConfiguration.class);

  /**
   * Where a record goes when it cannot be handled.
   *
   * <p>The partition is left to the producer rather than copied from the record. A DLQ topic has
   * fewer partitions than the topic it shadows (README section 10.1), so copying the index would
   * aim at a partition that may not exist; letting the key decide also keeps the messages of one
   * order in order in the DLQ, which is what makes a replay of them possible.
   */
  @Bean
  @ConditionalOnMissingBean
  public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
      KafkaTemplate<String, String> kafka) {
    return new DeadLetterPublishingRecoverer(kafka, ConsumerAutoConfiguration::deadLetterTopicOf);
  }

  @Bean
  @ConditionalOnMissingBean
  public CommonErrorHandler messageErrorHandler(
      DeadLetterPublishingRecoverer recoverer, ConsumerProperties properties) {
    DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOffOf(properties));
    // Everything the codec and the router refuse: the next attempt would refuse it identically.
    handler.addNotRetryableExceptions(MessagingException.class);
    return handler;
  }

  /**
   * The dead letter topic of the topic a record came from.
   *
   * <p>Built from the suffix rather than through {@link Topics#dlqOf(String)}, which refuses a
   * topic outside the catalogue: a resolver that threw would leave the record retried forever on a
   * blocked partition, and the one job of this function is to always have an answer. A record that
   * is already in a dead letter topic gets none, because a poison message that fails again has
   * nowhere further to go and must stay where it is.
   */
  // Package private so its two answers can be asserted directly: the second one, a record that is
  // already in a dead letter topic, is not reachable from a service that only subscribes to the
  // business topics.
  static TopicPartition deadLetterTopicOf(ConsumerRecord<?, ?> record, Exception failure) {
    String topic = record.topic();
    if (topic.endsWith(Topics.DLQ_SUFFIX)) {
      log.error(
          "a record of the dead letter topic {} could not be handled either, leaving it there",
          topic,
          failure);
      return null;
    }
    return new TopicPartition(topic + Topics.DLQ_SUFFIX, -1);
  }

  /**
   * The backoff between retries.
   *
   * <p>Doubling from the first pause up to the ceiling, which spreads a handful of attempts over a
   * few seconds: long enough for a dependency that is restarting, short enough that the partition
   * is not held while it waits.
   */
  private static BackOff backOffOf(ConsumerProperties properties) {
    ExponentialBackOff backOff = new ExponentialBackOff();
    backOff.setInitialInterval(properties.retryBackoff().toMillis());
    backOff.setMultiplier(2.0);
    backOff.setMaxInterval(properties.retryMaxBackoff().toMillis());
    // One backoff attempt is one retry: the pause is only computed before a redelivery, never
    // before the first one. A service asking for three retries therefore gets four deliveries in
    // total and then the DLQ.
    backOff.setMaxAttempts(properties.retries());
    // No ceiling on the elapsed time, because the attempt count is already the bound and two
    // bounds would make which one stopped the retries depend on how slow the handler was.
    backOff.setMaxElapsedTime(Long.MAX_VALUE);
    return backOff;
  }
}
