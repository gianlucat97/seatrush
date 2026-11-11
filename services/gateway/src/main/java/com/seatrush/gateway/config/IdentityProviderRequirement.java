package com.seatrush.gateway.config;

import com.seatrush.security.config.UserTokenProperties;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.security.tokens.UserTokens;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * What the {@code k8s} profile means, enforced rather than described (README sections 13 and 22).
 *
 * <p>README section 13 says a deployment on Kubernetes reads RS256 user tokens from an identity
 * provider through its JWKS endpoint, and section 22 says the {@code k8s} profile "brings its own
 * identity provider". Until this class existed the profile did one thing: it switched the demo
 * login off, because {@code DemoLoginController} carries {@code @Profile({"local","bench"})}. The
 * rest was a sentence in a document.
 *
 * <p>So this is the sentence as a startup check. In the {@code k8s} profile, an identity provider
 * has to be configured, and a gateway that has none refuses to start with the reason.
 *
 * <p><b>Why failing is better than falling back.</b> With no provider configured, the HS256 signer
 * of {@code libs/security} is what wires instead, and it generates a secret when none is set. On a
 * cluster that is the worst of the three possible outcomes: the demo login is off, so nobody can
 * obtain a token, and the gateway is nonetheless holding a key it would accept tokens signed with.
 * Every request answers {@code 401} and the deployment looks healthy doing it, because a probe
 * calls the actuator and the actuator needs no token. A process that exits with a sentence beats a
 * fleet of pods that are ready and useless.
 *
 * <p>The three facts a provider needs, the endpoint, the issuer and the audience, are demanded by
 * {@link UserTokenProperties.Jwks} itself, so a half-configured one also fails here rather than at
 * the first request. This class only checks that there is one at all.
 */
@Configuration(proxyBeanMethods = false)
@Profile("k8s")
public class IdentityProviderRequirement implements InitializingBean {

  private static final Logger log = LoggerFactory.getLogger(IdentityProviderRequirement.class);

  private final UserTokenProperties properties;
  private final UserTokens users;

  public IdentityProviderRequirement(UserTokenProperties properties, UserTokens users) {
    this.properties = properties;
    this.users = users;
  }

  @Override
  public void afterPropertiesSet() {
    if (!properties.trustsAnIdentityProvider()) {
      throw new IllegalStateException(
          """
          the k8s profile is active and no identity provider is configured.

          This profile has no demo login (README section 12.1), so nothing in this deployment can \
          issue a user token, and without seatrush.auth.jwks.uri nothing can read one either: \
          every request would answer 401 while the pods reported themselves ready.

          Set these three, which have no defaults because each one is a fact about your provider:
            SEATRUSH_AUTH_JWKS_URI       where it publishes its keys
            SEATRUSH_AUTH_JWKS_ISSUER    the iss claim this deployment accepts
            SEATRUSH_AUTH_JWKS_AUDIENCE  the aud claim this deployment is

          To run a cluster as a demo instead, with the login enabled, use the local profile, \
          which is what make k8s-up does (README section 22).""");
    }
    if (users instanceof UserTokenSigner) {
      // Unreachable while libs/security wires one or the other, and worth failing on rather than
      // trusting: this is the condition the check above exists to prevent, read off the bean that
      // was actually created rather than off the properties it was created from.
      throw new IllegalStateException(
          "the k8s profile is active and the gateway is holding a signing key: it must read "
              + "identities and never be able to mint one");
    }
    log.info(
        "user identities come from the configured identity provider, and this process "
            + "holds no key that could mint one");
  }
}
