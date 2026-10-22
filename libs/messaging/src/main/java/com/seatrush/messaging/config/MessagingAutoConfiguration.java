package com.seatrush.messaging.config;

import com.seatrush.common.config.CommonAutoConfiguration;
import com.seatrush.common.id.IdGenerator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import java.time.Clock;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the two beans a producer or a consumer injects: the envelope factory and the codec.
 *
 * <p>Both depend only on the clock and the id generator of {@code seatrush-common}, so adding this
 * dependency is enough. A service that needs a different mapper, for instance to add a module of
 * its own, declares its own {@link MessageCodec} and this configuration steps aside.
 */
@AutoConfiguration(after = CommonAutoConfiguration.class)
public class MessagingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public MessageFactory messageFactory(IdGenerator ids, Clock clock) {
    return new MessageFactory(ids, clock);
  }

  @Bean
  @ConditionalOnMissingBean
  public MessageCodec messageCodec() {
    return new MessageCodec();
  }
}
