package com.seatrush.security.config;

import com.seatrush.security.tokens.JwksUserTokens;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.security.tokens.UserTokens;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Contributes one way of reading a user identity to the service that imports it.
 *
 * <p>Imported and not auto-configured, unlike the ticket signer next door, because of what the
 * absence of a secret has to do. A signer with a generated secret is the right answer on a laptop
 * and it comes with a warning that it is useless anywhere else; auto-configuring it would print
 * that warning in every service that happens to depend on this module for something else, which
 * would teach everybody to ignore it. Only the gateway reads user tokens, so only the gateway asks
 * for this.
 *
 * <p>Two beans and never both, decided by whether {@code seatrush.auth.jwks.uri} is set. The one
 * that is chosen is a {@link UserTokens}, which is all the gateway's authentication filter asks
 * for; the HS256 one happens also to be a {@link UserTokenSigner}, which is what the demo login
 * needs, and that asymmetry is the point. A deployment trusting an identity provider gets no
 * signing key at all, so there is nothing in the process that could mint an identity even by
 * mistake, and the demo login fails to wire rather than quietly issuing tokens nobody downstream
 * would accept.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(UserTokenProperties.class)
public class UserTokenConfiguration {

  /**
   * Whether an identity provider is configured, as a condition the two beans below share.
   *
   * <p>Blank counts as absent, and that is the part worth spelling out.
   * {@code @ConditionalOnProperty} treats a property that is present and empty as set, and an empty
   * property is precisely what a deployment produces: the Helm chart of README section 24 renders
   * every value of its map, so a release left on the {@code local} profile passes {@code
   * SEATRUSH_AUTH_JWKS_URI=""}. Reading that as a configured provider would build a verifier
   * pointed at nothing and fail the startup of a deployment that never asked for one.
   */
  private static final String PROVIDER_CONFIGURED = "'${seatrush.auth.jwks.uri:}'.trim() != ''";

  /**
   * The identity provider of a deployment facing real users (README section 13).
   *
   * <p>Conditional on the one property that cannot be guessed. The two beside it, the issuer and
   * the audience, are demanded by {@link UserTokenProperties.Jwks} itself rather than here, so a
   * half-configured provider fails at startup with the reason rather than at the first request.
   */
  @Bean
  @ConditionalOnExpression(PROVIDER_CONFIGURED)
  @ConditionalOnMissingBean(UserTokens.class)
  public UserTokens jwksUserTokens(UserTokenProperties properties, Clock clock) {
    UserTokenProperties.Jwks jwks = properties.jwks();
    return new JwksUserTokens(
        jwks.uri(),
        jwks.issuer(),
        jwks.audience(),
        jwks.usernameClaim(),
        jwks.rolesClaim(),
        jwks.cacheTtl(),
        clock);
  }

  /**
   * The HS256 signer of a laptop and a benchmark, which both mints and reads.
   *
   * <p>The condition is exactly the inverse of the one above, so one of the two beans always exists
   * and never both.
   */
  @Bean
  @ConditionalOnExpression("!(" + PROVIDER_CONFIGURED + ")")
  @ConditionalOnMissingBean(UserTokens.class)
  public UserTokenSigner userTokenSigner(UserTokenProperties properties, Clock clock) {
    return new UserTokenSigner(
        ConfiguredSecret.resolve(properties.secret(), "seatrush.auth.secret"),
        clock,
        properties.tokenTtl());
  }
}
