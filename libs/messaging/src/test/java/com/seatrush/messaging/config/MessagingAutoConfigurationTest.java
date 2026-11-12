package com.seatrush.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.config.CommonAutoConfiguration;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageEnvelope;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.SampleMessages;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class MessagingAutoConfigurationTest {

  private static final Instant ON_SALE = Instant.parse("2026-06-01T10:00:00Z");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  CommonAutoConfiguration.class, MessagingAutoConfiguration.class));

  @Test
  void providesTheFactoryAndTheCodecOnTopOfTheCommonBeans() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(MessageFactory.class).hasSingleBean(MessageCodec.class);

          MessageEnvelope<CommitSeats> envelope =
              context.getBean(MessageFactory.class).wrap(SampleMessages.commitSeats());
          assertThat(envelope.type()).isEqualTo("CommitSeats");
          assertThat(
                  context
                      .getBean(MessageCodec.class)
                      .decode(context.getBean(MessageCodec.class).encode(envelope)))
              .isEqualTo(envelope);
        });
  }

  @Test
  void stampsEnvelopesWithTheClockOfTheService() {
    runner
        .withUserConfiguration(FixedClockConfiguration.class)
        .run(
            context ->
                assertThat(
                        context
                            .getBean(MessageFactory.class)
                            .wrap(SampleMessages.commitSeats())
                            .occurredAt())
                    .isEqualTo(ON_SALE));
  }

  @Test
  void stepsAsideWhenTheServiceDeclaresItsOwnCodec() {
    runner
        .withUserConfiguration(OwnCodecConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(MessageCodec.class);
              assertThat(context.getBean(MessageCodec.class))
                  .isSameAs(context.getBean(OwnCodecConfiguration.class).codec);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class FixedClockConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(ON_SALE, ZoneOffset.UTC);
    }
  }

  @Configuration(proxyBeanMethods = false)
  static class OwnCodecConfiguration {

    private final MessageCodec codec = new MessageCodec(MessageCodec.defaultMapper());

    @Bean
    MessageCodec messageCodec() {
      return codec;
    }
  }
}
