package com.seatrush.security.tokens;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.KeySourceException;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.JWKSourceBuilder;
import com.nimbusds.jose.jwk.source.RateLimitReachedException;
import com.nimbusds.jose.proc.BadJOSEException;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.nimbusds.jwt.proc.BadJWTException;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a user JWT that somebody else signed, against the RS256 keys they publish (README section
 * 13).
 *
 * <p>This is the identity half of a deployment that faces real users. The demo login of README
 * section 12.1 is switched off there, nothing in this system mints a user identity any more, and
 * the gateway's job narrows to checking one that arrived: the signature against a key fetched from
 * the provider's JWKS endpoint, the issuer, the audience and the expiry.
 *
 * <p><b>The algorithm is pinned to RS256 and never read out of the token.</b> Same argument as
 * {@link JwtCodec} makes for HS256 and the same family of bugs: a verifier that honoured the {@code
 * alg} header would accept whatever an attacker put there, and with a JWKS in play the sharpest
 * version of that is an HS256 token signed with the public key as its secret, which is published.
 * The key selector is built for one algorithm, so a token arriving as anything else is refused
 * before a key is even chosen.
 *
 * <p><b>The audience is demanded and has no default.</b> An identity provider issues tokens for
 * many applications out of one issuer and one key set, so the signature alone says only that the
 * provider signed something. Without the audience, a token minted for a different application of
 * the same provider would be a valid SeatRush identity. That is the same argument the two {@link
 * TokenAudience} values make inside this system, one level out.
 *
 * <p><b>Expiry is checked with no tolerance for clock skew</b>, matching {@link JwtCodec}, and
 * against the injected clock so a test is a line of code. {@code nbf} is honoured when the provider
 * sets one, because unlike the millisecond-old tokens queue-service mints, a token from an external
 * provider genuinely may not be valid yet.
 *
 * <p><b>The user id is derived from the issuer and the subject</b>, not taken from the subject. The
 * reason is that {@link UserToken#userId()} is a UUID and becomes the {@code X-User-Id} that every
 * service behind the gateway keys its rows on, while an identity provider's {@code sub} is whatever
 * that provider chose: {@code auth0|65f3...}, a number, an email. Hashing it into a UUID the way
 * the demo login hashes a username keeps the one property that matters, which is that the same
 * person is always the same buyer: a buyer who reloads the page must not lose the hold, the order
 * and the tickets they just paid for, all of which are keyed by that id. The issuer is part of what
 * is hashed so that two providers cannot collide on one subject.
 *
 * <p>Keys are fetched once and cached, refreshed when a token arrives with a key id the cache does
 * not know, and that refresh is rate limited so a stream of tokens naming a nonexistent key cannot
 * turn into a stream of requests to the provider. All of that is Nimbus's {@link JWKSourceBuilder};
 * what is written here is the configuration of it and the reasons.
 */
public final class JwksUserTokens implements UserTokens {

  private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.RS256;

  /**
   * What a subject is hashed with before it becomes a user id.
   *
   * <p>Not a secret and not protecting anything, exactly like the namespace the demo login uses. It
   * is there so that an id derived here cannot collide with an id derived there, or with one from
   * another system that happened to hash the same string.
   */
  private static final String NAMESPACE = "seatrush:oidc:";

  private static final Logger log = LoggerFactory.getLogger(JwksUserTokens.class);

  private final ConfigurableJWTProcessor<SecurityContext> processor;
  private final Clock clock;
  private final String issuer;
  private final String usernameClaim;
  private final String rolesClaim;

  /**
   * @param jwksUri where the provider publishes its keys
   * @param issuer the {@code iss} this deployment accepts, and nothing else
   * @param audience the {@code aud} this deployment is, which is the application id the provider
   *     knows SeatRush by
   * @param usernameClaim which claim carries a display name
   * @param rolesClaim which claim carries role names
   * @param cacheTtl how long a fetched key set is used before it is fetched again
   */
  public JwksUserTokens(
      URI jwksUri,
      String issuer,
      String audience,
      String usernameClaim,
      String rolesClaim,
      Duration cacheTtl,
      Clock clock) {
    Objects.requireNonNull(jwksUri, "jwksUri must not be null");
    this.issuer = requireText(issuer, "issuer");
    String expectedAudience = requireText(audience, "audience");
    this.usernameClaim = requireText(usernameClaim, "usernameClaim");
    this.rolesClaim = requireText(rolesClaim, "rolesClaim");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    Objects.requireNonNull(cacheTtl, "cacheTtl must not be null");
    if (cacheTtl.isNegative() || cacheTtl.isZero()) {
      throw new IllegalArgumentException("a key cache lifetime must be positive, was " + cacheTtl);
    }

    JWKSource<SecurityContext> keys =
        JWKSourceBuilder.create(urlOf(jwksUri))
            // Keys are cached for this long, and a token naming a key id the cache does not hold
            // triggers one refresh rather than being refused: that is what makes a provider's key
            // rotation invisible here instead of an outage at the moment it rotates.
            .cache(cacheTtl.toMillis(), Duration.ofSeconds(30).toMillis())
            .refreshAheadCache(false)
            // Rate limited, so a stream of tokens naming a key that does not exist cannot become a
            // stream of requests to the provider. Without it, a forged token is a way to make the
            // gateway attack its own identity provider.
            .rateLimited(Duration.ofSeconds(30).toMillis())
            // One retry, because the common failure is a single lost connection and the uncommon
            // one is a provider that is down, which retrying does not fix.
            .retrying(true)
            .build();

    DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
    processor.setJWSKeySelector(new JWSVerificationKeySelector<>(ALGORITHM, keys));
    // The claims are verified inside the processor rather than after it, so that a token failing
    // any of them never reaches the code that reads an identity out of it.
    //
    // Written here rather than configured as Nimbus's DefaultJWTClaimsVerifier, and the reason is
    // the clock. That class reads the system clock and allows a minute of leeway, and this system
    // checks expiry against the injected clock with no tolerance for skew (JwtCodec says why). A
    // deployment cannot have the one behaviour on one side of the gateway and another on the
    // other, and a test cannot move a clock it does not own.
    processor.setJWTClaimsSetVerifier(new Claims(this.issuer, expectedAudience, this.clock));
    this.processor = processor;

    log.info(
        "user identities are read as {} against the key set at {}, issued by {} for {}",
        ALGORITHM,
        jwksUri,
        this.issuer,
        expectedAudience);
  }

  @Override
  public UserToken verify(String token) {
    Objects.requireNonNull(token, "token must not be null");

    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException notAJwt) {
      throw new TokenException("a user token that is not a signed JWT", notAJwt);
    }
    // Checked before the processor is asked, so that the refusal of a token arriving as HS256 or
    // as `alg: none` does not depend on how a key selector happens to behave with no key.
    if (!ALGORITHM.equals(jwt.getHeader().getAlgorithm())) {
      throw new TokenException(
          "a user token signed with "
              + jwt.getHeader().getAlgorithm()
              + " rather than "
              + ALGORITHM);
    }

    JWTClaimsSet claims;
    try {
      claims = processor.process(jwt, null);
    } catch (BadJOSEException refused) {
      // Every claim check of Claims below, plus the key selection: a token arriving with a key id
      // the provider does not publish lands here too.
      throw new TokenException(
          "a user token this deployment does not accept: " + refused.getMessage(), refused);
    } catch (RateLimitReachedException alreadyRefreshed) {
      // The key set was refreshed recently and this token still names a key that is not in it, so
      // the rate limiter declined to ask again. That is a verdict about the token rather than about
      // the provider, and it is the shape a forged token takes: logged at debug, because a stack
      // trace per forged request is a log flood somebody else gets to choose the size of.
      log.debug(
          "a user token named a key the recently fetched key set does not hold", alreadyRefreshed);
      throw new TokenException(
          "a user token naming a key this deployment's provider does not publish",
          alreadyRefreshed);
    } catch (KeySourceException unreachable) {
      // A JWKS endpoint that cannot be read is not a token this caller got wrong, and it is worth
      // saying so differently. It is still a refusal: a gateway that let a request through because
      // it could not check the signature would be no gateway at all.
      log.warn(
          "the key set could not be read, so a user token could not be checked: {}",
          unreachable.getMessage());
      throw new TokenException(
          "a user token that could not be checked against the key set", unreachable);
    } catch (JOSEException unreadable) {
      throw new TokenException("a user token whose signature could not be checked", unreadable);
    }

    return new UserToken(
        userIdOf(claims.getSubject()),
        usernameOf(claims),
        rolesOf(claims),
        issuedAtOf(claims),
        claims.getExpirationTime().toInstant());
  }

  /**
   * The same person is always the same buyer.
   *
   * <p>Derived rather than parsed, for the reason in this class's own documentation: downstream
   * rows are keyed on a UUID, and a provider's subject is whatever the provider chose.
   */
  private UUID userIdOf(String subject) {
    String canonical = NAMESPACE + issuer + ":" + subject;
    return UUID.nameUUIDFromBytes(canonical.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A display name, falling back to the subject when the provider publishes none.
   *
   * <p>A missing display name is not a malformed token: plenty of providers put nothing readable in
   * a token, and {@link UserToken} wants a non-blank name only so that the client has something to
   * show. The subject is the honest fallback, and refusing the identity over it would be refusing a
   * buyer for how their provider is configured.
   */
  private String usernameOf(JWTClaimsSet claims) {
    Object value = claims.getClaim(usernameClaim);
    if (value instanceof String name && !name.isBlank()) {
      return name.strip();
    }
    return claims.getSubject();
  }

  /**
   * Role names, as a list or as the single space-delimited string some providers write.
   *
   * <p>Absent is none, which is what an ordinary buyer has. A claim of a shape neither of those is
   * a refusal rather than an empty set: a token that says something about roles in a form this
   * build cannot read must not be silently treated as a buyer with none, because the question
   * anybody asks of these is whether the caller is an administrator.
   */
  private Set<String> rolesOf(JWTClaimsSet claims) {
    Object value = claims.getClaim(rolesClaim);
    if (value == null) {
      return Set.of();
    }
    if (value instanceof String scopes) {
      return Set.copyOf(List.of(scopes.strip().split("\\s+")));
    }
    try {
      List<String> roles = claims.getStringListClaim(rolesClaim);
      return roles == null ? Set.of() : Set.copyOf(roles);
    } catch (ParseException notRoles) {
      throw new TokenException(
          "a user token whose " + rolesClaim + " claim is neither a list nor a string", notRoles);
    }
  }

  /**
   * When the token was issued, and now when the provider did not say.
   *
   * <p>{@link JwtCodec} requires {@code iat} because this system writes it. An external provider is
   * not obliged to, and {@link UserToken} carries the instant only so a client can show it.
   */
  private java.time.Instant issuedAtOf(JWTClaimsSet claims) {
    Date issuedAt = claims.getIssueTime();
    return issuedAt == null ? clock.instant() : issuedAt.toInstant();
  }

  /**
   * Issuer, audience, subject and the two instants, against the injected clock.
   *
   * <p>Four refusals and each one closes a different door. A wrong issuer is a token from another
   * provider. A wrong audience is a token the right provider minted for a different application,
   * which is the one a signature check alone cannot catch. A missing subject is not an identity. An
   * expired or not-yet-valid token is one nobody should act on.
   */
  private record Claims(String issuer, String audience, Clock clock)
      implements com.nimbusds.jwt.proc.JWTClaimsSetVerifier<SecurityContext> {

    @Override
    public void verify(JWTClaimsSet claims, SecurityContext context) throws BadJWTException {
      if (!issuer.equals(claims.getIssuer())) {
        throw new BadJWTException(
            "a user token issued by " + claims.getIssuer() + " rather than " + issuer);
      }
      List<String> presented = claims.getAudience();
      if (presented == null || !presented.contains(audience)) {
        throw new BadJWTException("a user token for " + presented + " rather than for " + audience);
      }
      if (claims.getSubject() == null || claims.getSubject().isBlank()) {
        throw new BadJWTException("a user token with no subject");
      }
      Date expiresAt = claims.getExpirationTime();
      if (expiresAt == null) {
        throw new BadJWTException("a user token that never expires");
      }
      if (!clock.instant().isBefore(expiresAt.toInstant())) {
        throw new BadJWTException("a user token that expired at " + expiresAt.toInstant());
      }
      Date notBefore = claims.getNotBeforeTime();
      if (notBefore != null && clock.instant().isBefore(notBefore.toInstant())) {
        throw new BadJWTException("a user token not valid before " + notBefore.toInstant());
      }
    }
  }

  private static String requireText(String value, String what) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(what + " must not be blank");
    }
    return value.strip();
  }

  private static URL urlOf(URI uri) {
    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!scheme.equals("https") && !scheme.equals("http")) {
      throw new IllegalArgumentException(
          "a JWKS endpoint has to be an http or https URL, and this one is " + uri);
    }
    try {
      return uri.toURL();
    } catch (MalformedURLException notAUrl) {
      throw new IllegalArgumentException("a JWKS endpoint that is not a URL: " + uri, notAUrl);
    }
  }
}
