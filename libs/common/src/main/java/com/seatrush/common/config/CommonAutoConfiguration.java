package com.seatrush.common.config;

import com.seatrush.common.id.IdGenerator;
import com.seatrush.common.id.UuidV7Generator;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the two beans every service injects: the clock and the id generator.
 *
 * <p>Adding the {@code seatrush-common} dependency is enough; a service that needs different
 * behaviour, such as a test with a fixed clock, declares its own bean and this configuration steps
 * aside.
 */
@AutoConfiguration
public class CommonAutoConfiguration {

  /**
   * The single source of time, in UTC.
   *
   * <p>Nothing in SeatRush reads the wall clock directly: hold expiry, admission token TTLs and
   * SAGA timeouts all compare instants taken from this bean, so a test can move time instead of
   * waiting for it.
   */
  @Bean
  @ConditionalOnMissingBean
  public Clock clock() {
    return Clock.systemUTC();
  }

  @Bean
  @ConditionalOnMissingBean
  public IdGenerator idGenerator(Clock clock) {
    return new UuidV7Generator(clock);
  }
}
