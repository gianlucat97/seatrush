package com.seatrush.security.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * How a user identity is established, and how long one lasts (README sections 13 and 22).
 *
 * <p>Two mutually exclusive halves, and which one a deployment uses is decided by whether {@code
 * jwks.uri} is set rather than by a flag. With it unset, this system mints its own HS256 tokens
 * through the demo login of README section 12.1 and reads them back with {@code secret}. With it
 * set, nothing here mints an identity at all: the tokens arrive from somebody else's identity
 * provider, signed RS256, and the gateway only checks them.
 *
 * <p>The secret is optional for the reason the ticket key is: a developer running the stack has no
 * secret to give it, and refusing to start would make the local stack unusable for the sake of a
 * check Kubernetes already does. What happens with nothing set is that one is generated and the
 * service says what that costs.
 *
 * <p>An hour by default. A user token is only an identity, so its lifetime trades a re-login
 * against how long a stolen one is useful; what actually bounds a buyer is the admission token,
 * which lasts minutes. A demo that expired mid-checkout would be the worse failure of the two. It
 * applies to minting only: a token from an identity provider lasts as long as that provider said.
 *
 * @param secret base64 of at least 32 bytes, from a secret store and never from the repo
 * @param tokenTtl how long a token this system mints is accepted after it was issued
 * @param jwks the identity provider to trust instead of minting, when there is one
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.auth")
public record UserTokenProperties(
    String secret, @NotNull @DefaultValue("1h") Duration tokenTtl, @Valid @DefaultValue Jwks jwks) {

  public UserTokenProperties {
    if (tokenTtl == null || tokenTtl.isZero() || tokenTtl.isNegative()) {
      throw new IllegalArgumentException(
          "seatrush.auth.token-ttl must be positive, was " + tokenTtl);
    }
  }

  /** Whether this deployment trusts an identity provider rather than minting its own tokens. */
  public boolean trustsAnIdentityProvider() {
    return jwks != null && jwks.uri() != null;
  }

  /**
   * The RS256 identity provider of a deployment that faces real users.
   *
   * <p>Three of these have no default and that is deliberate. A JWKS endpoint, an issuer and an
   * audience are the three facts that make a token from a provider mean this deployment, and a
   * default for any of them would be a deployment that accepts more than it meant to. The audience
   * in particular: a provider signs tokens for every application it serves out of one key set, so
   * without it the signature says only that the provider signed something.
   *
   * @param uri where the provider publishes its keys
   * @param issuer the {@code iss} claim this deployment accepts, and nothing else
   * @param audience the {@code aud} claim this deployment is, which is the application id the
   *     provider knows SeatRush by
   * @param usernameClaim which claim carries a display name; the subject is used when it is absent
   * @param rolesClaim which claim carries role names, as a list or as one space-delimited string
   * @param cacheTtl how long a fetched key set is used before it is fetched again. A token naming a
   *     key the cache does not hold triggers a refresh regardless, which is what makes a provider's
   *     key rotation invisible rather than an outage
   */
  public record Jwks(
      URI uri,
      String issuer,
      String audience,
      @NotNull @DefaultValue("preferred_username") String usernameClaim,
      @NotNull @DefaultValue("roles") String rolesClaim,
      @NotNull @DefaultValue("15m") Duration cacheTtl) {

    public Jwks {
      // A deployment that passes an empty value has configured nothing, and URI.create("") is a
      // perfectly legal URI that points nowhere. Normalised here so that one answer to "is there a
      // provider" serves the condition, the validation below and the verifier.
      if (uri != null && uri.toString().isBlank()) {
        uri = null;
      }
      if (uri != null) {
        if (issuer == null || issuer.isBlank()) {
          throw new IllegalArgumentException(
              "seatrush.auth.jwks.issuer must be set when a jwks uri is: a token whose issuer "
                  + "nothing checks is a token from anywhere");
        }
        if (audience == null || audience.isBlank()) {
          throw new IllegalArgumentException(
              "seatrush.auth.jwks.audience must be set when a jwks uri is: a provider signs "
                  + "tokens for every application it serves, so the signature alone does not say "
                  + "this one");
        }
      }
      if (cacheTtl != null && (cacheTtl.isZero() || cacheTtl.isNegative())) {
        throw new IllegalArgumentException(
            "seatrush.auth.jwks.cache-ttl must be positive, was " + cacheTtl);
      }
    }
  }
}
