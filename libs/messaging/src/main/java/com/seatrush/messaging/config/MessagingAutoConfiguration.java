package com.seatrush.messaging.config;

import com.seatrush.common.config.CommonAutoConfiguration;
import com.seatrush.common.id.IdGenerator;
import com.seatrush.messaging.MessageCodec;
import com.seatrush.messaging.MessageFactory;
import com.seatrush.messaging.tracing.MessageTracing;
import com.seatrush.messaging.tracing.MicrometerMessageTracing;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Registers the beans a producer or a consumer injects: the envelope factory, the codec and the
 * tracing that carries a trace across the outbox.
 *
 * <p>The first two depend only on the clock and the id generator of {@code seatrush-common}, so
 * adding this dependency is enough. A service that needs a different mapper, for instance to add a
 * module of its own, declares its own {@link MessageCodec} and this configuration steps aside.
 */
@AutoConfiguration(after = CommonAutoConfiguration.class)
public class MessagingAutoConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public MessageFactory messageFactory(IdGenerator ids, Clock clock, MessageTracing tracing) {
    return new MessageFactory(ids, clock, tracing);
  }

  /**
   * The tracing of README section 14.2, or nothing when this build has no tracer.
   *
   * <p>Both collaborators arrive as providers and are resolved when this method runs rather than
   * when the condition is evaluated. A {@code @ConditionalOnBean} would have to be ordered after
   * whichever auto-configuration contributes the tracer, and that is a class name in another
   * module's internals: the day it moves, tracing would go quietly missing instead of failing.
   *
   * <p>A service with the Micrometer API on its classpath and no backend configured has neither
   * bean, and gets {@link MessageTracing#none()}. That is also what every unit test gets.
   */
  @Bean
  @ConditionalOnMissingBean
  public MessageTracing messageTracing(
      ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
    Tracer resolved = tracer.getIfAvailable();
    Propagator propagation = propagator.getIfAvailable();
    return resolved == null || propagation == null
        ? MessageTracing.none()
        : new MicrometerMessageTracing(resolved, propagation);
  }

  @Bean
  @ConditionalOnMissingBean
  public MessageCodec messageCodec() {
    return new MessageCodec();
  }
}
