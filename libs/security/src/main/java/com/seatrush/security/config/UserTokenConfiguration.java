package com.seatrush.security.config;

import com.seatrush.security.tokens.UserTokenSigner;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes the user token signer to a service that imports it.
 *
 * <p>Imported and not auto-configured, unlike the ticket signer next door, because of what the
 * absence of a secret has to do. A signer with a generated secret is the right answer on a laptop
 * and it comes with a warning that it is useless anywhere else; auto-configuring it would print
 * that warning in every service that happens to depend on this module for something else, which
 * would teach everybody to ignore it. Only the gateway mints and reads user tokens, so only the
 * gateway asks for this.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserTokenProperties.class)
public class UserTokenConfiguration {

  @Bean
  @ConditionalOnMissingBean
  public UserTokenSigner userTokenSigner(UserTokenProperties properties, Clock clock) {
    return new UserTokenSigner(
        ConfiguredSecret.resolve(properties.secret(), "seatrush.auth.secret"),
        clock,
        properties.tokenTtl());
  }
}
