package com.seatrush.security.config;

import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * The secret user JWTs are signed with, and how long a login lasts (README sections 13 and 22).
 *
 * <p>The secret is optional for the reason the ticket key is: a developer running the stack has no
 * secret to give it, and refusing to start would make the local stack unusable for the sake of a
 * check Kubernetes already does. What happens with nothing set is that one is generated and the
 * service says what that costs.
 *
 * <p>An hour by default. A user token is only an identity, so its lifetime trades a re-login
 * against how long a stolen one is useful; what actually bounds a buyer is the admission token,
 * which lasts minutes. A demo that expired mid-checkout would be the worse failure of the two.
 *
 * @param secret base64 of at least 32 bytes, from a secret store and never from the repo
 * @param tokenTtl how long a token is accepted after it was issued
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.auth")
public record UserTokenProperties(String secret, @NotNull @DefaultValue("1h") Duration tokenTtl) {

  public UserTokenProperties {
    if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.auth.token-ttl must be positive, was " + tokenTtl);
    }
  }
}
