package com.seatrush.payment.config;

import com.seatrush.payment.adapters.out.provider.SimulatedPaymentProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The simulated provider, built once from the configuration it starts with.
 *
 * <p>Declared here rather than annotated, because it is one object serving two ports and both of
 * them have to be the same instance: the endpoint that turns the knobs and the handler that reads
 * them would otherwise be looking at different settings.
 */
@Configuration(proxyBeanMethods = false)
public class PaymentProviderConfiguration {

  @Bean
  public SimulatedPaymentProvider simulatedPaymentProvider(PaymentProperties properties) {
    return new SimulatedPaymentProvider(properties.asSettings());
  }
}
