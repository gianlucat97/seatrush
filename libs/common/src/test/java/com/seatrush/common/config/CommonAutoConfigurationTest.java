package com.seatrush.common.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7;
import com.seatrush.common.id.UuidV7Generator;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

class CommonAutoConfigurationTest {

  private static final Instant ON_SALE = Instant.parse("2026-10-21T19:07:38Z");

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(CommonAutoConfiguration.class));

  @Test
  void providesAUtcClockAndATimeOrderedIdGenerator() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(Clock.class).hasSingleBean(IdGenerator.class);
          assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneOffset.UTC);
          assertThat(context.getBean(IdGenerator.class)).isInstanceOf(UuidV7Generator.class);
          assertThat(context.getBean(IdGenerator.class).newId().version()).isEqualTo(7);
        });
  }

  @Test
  void stepsAsideWhenTheServiceDeclaresItsOwnClock() {
    runner
        .withUserConfiguration(FixedClockConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(Clock.class);
              assertThat(context.getBean(Clock.class).instant()).isEqualTo(ON_SALE);
              assertThat(UuidV7.timestampOf(context.getBean(IdGenerator.class).newId()))
                  .isEqualTo(ON_SALE);
            });
  }

  @Configuration(proxyBeanMethods = false)
  static class FixedClockConfiguration {

    @Bean
    Clock clock() {
      return Clock.fixed(ON_SALE, ZoneOffset.UTC);
    }
  }
}
