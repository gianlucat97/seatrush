package com.seatrush.security.config;

import com.seatrush.security.tokens.AdmissionTokenSigner;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the admission token signer to a service that imports it, which is queue-service and
 * the gateway.
 *
 * <p>Imported for the reason {@link UserTokenConfiguration} is, and the same bean serves both
 * sides: queue-service calls {@code issue} and never {@code verify}, the gateway the other way
 * round. Splitting it into an issuer and a verifier would be two classes agreeing about a format,
 * which is one more place for them to stop agreeing.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AdmissionTokenProperties.class)
public class AdmissionTokenConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public AdmissionTokenSigner admissionTokenSigner(
      AdmissionTokenProperties properties, Clock clock) {
    return new AdmissionTokenSigner(
        ConfiguredSecret.resolve(properties.secret(), "seatrush.admission.secret"),
        clock,
        properties.tokenTtl());
  }
}
