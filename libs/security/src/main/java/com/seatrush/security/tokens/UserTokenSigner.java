package com.seatrush.security.tokens;

import com.nimbusds.jwt.JWTClaimsSet;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints and checks the user JWT (README sections 12.1 and 13).
 *
 * <p>Minting is the demo login, and the demo login exists in the {@code local} and {@code bench}
 * profiles only: a portfolio system that asked for a password would be demonstrating a login form
 * rather than an on-sale. On Kubernetes the profile disables that endpoint and the tokens arrive
 * from an identity provider, verified through its JWKS endpoint by the gateway's resource server.
 * The checking side here is therefore the one that disappears in production, and the record it
 * produces, {@link UserToken}, is what stays.
 *
 * <p>Verification is deliberately kept next to issuing rather than left to the gateway alone,
 * because something has to be able to read a token back in a test without a running gateway, and
 * because a token format with two implementations has two behaviours.
 */
public final class UserTokenSigner {

  static final String USERNAME_CLAIM = "username";
  static final String ROLES_CLAIM = "roles";

  private final JwtCodec codec;

  /**
   * @param secret the HS256 secret of {@link TokenSecrets}, never the admission one
   * @param ttl how long a login lasts
   */
  public UserTokenSigner(SecretKeySpec secret, Clock clock, Duration ttl) {
    this.codec = new JwtCodec(secret, TokenAudience.USER, clock, ttl);
  }

  /** How long the tokens this signer mints last. */
  public Duration ttl() {
    return codec.ttl();
  }

  /** A token for a user who has just logged in. */
  public IssuedToken<UserToken> issue(UUID userId, String username, Set<String> roles) {
    Objects.requireNonNull(username, "username must not be null");
    Objects.requireNonNull(roles, "roles must not be null");

    JwtCodec.Signed signed =
        codec.sign(
            userId,
            new JWTClaimsSet.Builder()
                .claim(USERNAME_CLAIM, username)
                .claim(ROLES_CLAIM, List.copyOf(roles)));
    return new IssuedToken<>(
        signed.compact(),
        new UserToken(userId, username, roles, signed.issuedAt(), signed.expiresAt()));
  }

  /**
   * Reads a user token back.
   *
   * @throws TokenException if it was not signed by this secret for users, has expired, or is
   *     missing a claim this build needs
   */
  public UserToken verify(String token) {
    JWTClaimsSet claims = codec.verify(token);
    return new UserToken(
        codec.subject(claims),
        codec.claim(claims, USERNAME_CLAIM, String.class),
        roles(claims),
        claims.getIssueTime().toInstant(),
        claims.getExpirationTime().toInstant());
  }

  /**
   * The roles of a token, and none when it carries no such claim.
   *
   * <p>Absent is not malformed: an identity provider minting a token for an ordinary buyer has no
   * reason to write an empty array, and a buyer is what every endpoint but README section 12.2
   * expects anyway.
   */
  private static Set<String> roles(JWTClaimsSet claims) {
    try {
      List<String> roles = claims.getStringListClaim(ROLES_CLAIM);
      return roles == null ? Set.of() : Set.copyOf(roles);
    } catch (ParseException notRoles) {
      throw new TokenException("a user token whose roles claim is not a list of names", notRoles);
    }
  }
}
