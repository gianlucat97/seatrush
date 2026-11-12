package com.seatrush.security.tokens;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.JWSVerifier;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.MACVerifier;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.text.ParseException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Objects;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

/**
 * The HS256 signing and checking that the user JWT and the admission token share.
 *
 * <p>JWT and not a format of our own, because the thing reading these is a gateway and the thing
 * minting the user token may one day not be us: README section 13 already puts an RS256 JWKS
 * endpoint in front of it on Kubernetes, and a bespoke format would have to be translated the day
 * that arrives. A ticket could afford its own format ({@link
 * com.seatrush.security.tickets.TicketSigner}) precisely because nothing outside this system reads
 * one.
 *
 * <p>Nimbus and not a hand-rolled implementation, because the failure modes of JWT live in the
 * parsing and not in the cryptography. It is also the library Spring Security's resource server
 * uses underneath, so the gateway ends up holding one JOSE implementation rather than two.
 *
 * <p>What is checked, in this order, and why that order:
 *
 * <ol>
 *   <li><b>The algorithm is exactly HS256.</b> Read from the header and compared to a constant,
 *       never used to decide how to verify. That inversion is the alg-confusion family of bugs, of
 *       which {@code alg: none} is only the famous member: a {@link MACVerifier} on its own would
 *       also accept HS512 from a secret long enough to carry it.
 *   <li><b>The signature.</b> Before any claim is read, so a forged token is never interpreted.
 *   <li><b>The issuer and the audience.</b> The audience is what refuses a user JWT presented where
 *       an admission token belongs (see {@link TokenAudience}).
 *   <li><b>Expiry, against the injected clock.</b>
 * </ol>
 *
 * <p>Expiry is compared with no tolerance for clock skew, which is a deliberate departure from the
 * minute of leeway JOSE libraries default to. An admission token outliving its slot is the one
 * thing it must not do: by the time the expiry passes, the slot is already gone from the active set
 * in Redis, so a token honoured a minute later is a buyer inside the purchase area that nothing is
 * counting. A client whose token the gateway considers stale re-reads the queue endpoint and gets
 * the current one, which is a cheap recovery from a rare event.
 *
 * <p>Neither {@code iat} nor {@code nbf} is compared to the clock. A token minted by queue-service
 * and presented to the gateway a millisecond later would be rejected as not-yet-valid by any clock
 * running a second behind, and refusing a token for being too new protects nobody. {@code iat} is
 * still required to be there, because a caller is told when its token was issued.
 */
final class JwtCodec {

  /**
   * Written into {@code iss} and demanded back, so a token of another system is not one of ours.
   */
  static final String ISSUER = "seatrush";

  private static final JWSAlgorithm ALGORITHM = JWSAlgorithm.HS256;

  private final JWSSigner signer;
  private final JWSVerifier verifier;
  private final TokenAudience audience;
  private final Clock clock;
  private final Duration ttl;

  JwtCodec(SecretKeySpec secret, TokenAudience audience, Clock clock, Duration ttl) {
    Objects.requireNonNull(secret, "secret must not be null");
    this.audience = Objects.requireNonNull(audience, "audience must not be null");
    this.clock = Objects.requireNonNull(clock, "clock must not be null");
    this.ttl = Objects.requireNonNull(ttl, "ttl must not be null");
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException(
          "a token lifetime must be positive, and this one is " + ttl);
    }
    try {
      this.signer = new MACSigner(secret);
      this.verifier = new MACVerifier(secret);
    } catch (JOSEException e) {
      // The secret was length checked when it was loaded, so this is the JVM and not the input.
      throw new IllegalStateException("this JVM cannot sign with " + ALGORITHM, e);
    }
  }

  /** How long the tokens this instance mints last, which a caller usually has to tell a client. */
  Duration ttl() {
    return ttl;
  }

  /**
   * Signs one token that lasts as long as this instance is configured for.
   *
   * @param subject the user the token is about, which becomes the {@code sub} claim
   * @param extraClaims the claims of this kind of token alone, already named by the caller
   */
  Signed sign(UUID subject, JWTClaimsSet.Builder extraClaims) {
    // Whole seconds, because that is all iat and exp can hold: JWT counts them in epoch seconds
    // and anything finer is dropped on the way out. Rounding here rather than letting the
    // serialiser do it is what keeps the instants reported to the caller equal to the ones the
    // verifying side will read.
    return signUntil(
        subject, extraClaims, clock.instant().truncatedTo(ChronoUnit.SECONDS).plus(ttl));
  }

  /**
   * Signs one token that dies at an instant the caller chose.
   *
   * <p>For the caller that is coordinating the token with something else of its own. queue-service
   * is the one that does: the instant an admission expires is the same instant that scores the
   * buyer's slot in the active set, and the two have to be the same instant rather than two
   * readings of a clock a microsecond apart. A token outliving its slot by even a second is a buyer
   * inside the purchase area that nothing is counting.
   *
   * @param expiresAt when the token stops being accepted, rounded down to the second a JWT can
   *     carry
   */
  Signed signUntil(UUID subject, JWTClaimsSet.Builder extraClaims, Instant expiresAt) {
    Objects.requireNonNull(subject, "subject must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    Instant issuedAt = clock.instant().truncatedTo(ChronoUnit.SECONDS);
    Instant expiry = expiresAt.truncatedTo(ChronoUnit.SECONDS);
    if (!issuedAt.isBefore(expiry)) {
      // A token born expired would be refused by its own verifier, so minting one is a bug in
      // the caller rather than an answer to give a client.
      throw new IllegalArgumentException(
          "a "
              + audience
              + " token cannot expire at "
              + expiry
              + ", which is not after "
              + issuedAt);
    }

    JWTClaimsSet claims =
        extraClaims
            .issuer(ISSUER)
            .audience(audience.claim())
            .subject(subject.toString())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(expiry))
            .build();

    SignedJWT jwt =
        new SignedJWT(new JWSHeader.Builder(ALGORITHM).type(JOSEObjectType.JWT).build(), claims);
    try {
      jwt.sign(signer);
    } catch (JOSEException e) {
      throw new IllegalStateException("could not sign a " + audience + " token", e);
    }
    return new Signed(jwt.serialize(), issuedAt, expiry);
  }

  /**
   * Reads a token back, refusing anything this secret did not sign for this audience.
   *
   * @throws TokenException whatever the reason
   */
  JWTClaimsSet verify(String token) {
    Objects.requireNonNull(token, "token must not be null");

    SignedJWT jwt;
    try {
      jwt = SignedJWT.parse(token);
    } catch (ParseException notAJwt) {
      throw new TokenException("a token that is not a signed JWT", notAJwt);
    }

    if (!ALGORITHM.equals(jwt.getHeader().getAlgorithm())) {
      throw new TokenException(
          "a token signed with " + jwt.getHeader().getAlgorithm() + " rather than " + ALGORITHM);
    }
    try {
      if (!jwt.verify(verifier)) {
        throw new TokenException("a token this secret did not sign");
      }
    } catch (JOSEException malformed) {
      // A signature of the wrong shape, which is a refusal and not a failure of this process.
      throw new TokenException("a token whose signature could not be read", malformed);
    }

    JWTClaimsSet claims;
    try {
      claims = jwt.getJWTClaimsSet();
    } catch (ParseException notClaims) {
      throw new TokenException("a signed token whose claims cannot be read", notClaims);
    }

    if (!ISSUER.equals(claims.getIssuer())) {
      throw new TokenException(
          "a token issued by " + claims.getIssuer() + " rather than " + ISSUER);
    }
    if (claims.getAudience() == null || !claims.getAudience().contains(audience.claim())) {
      throw new TokenException(
          "a token for " + claims.getAudience() + " presented as a " + audience + " token");
    }
    if (claims.getIssueTime() == null) {
      throw new TokenException("a token that does not say when it was issued");
    }
    Date expiresAt = claims.getExpirationTime();
    if (expiresAt == null) {
      throw new TokenException("a token that never expires");
    }
    if (!clock.instant().isBefore(expiresAt.toInstant())) {
      throw new TokenException("a " + audience + " token that expired at " + expiresAt.toInstant());
    }
    return claims;
  }

  /** A claim this kind of token is required to carry, of the shape it is required to have. */
  <T> T claim(JWTClaimsSet claims, String name, Class<T> type) {
    Object value = claims.getClaim(name);
    if (!type.isInstance(value)) {
      throw new TokenException(
          "a " + audience + " token with no " + type.getSimpleName() + " " + name + " claim");
    }
    return type.cast(value);
  }

  /** The {@code sub} claim, which both kinds carry and both read as a user id. */
  UUID subject(JWTClaimsSet claims) {
    try {
      return UUID.fromString(claims.getSubject());
    } catch (IllegalArgumentException | NullPointerException notAnId) {
      throw new TokenException("a " + audience + " token whose subject is not a user id", notAnId);
    }
  }

  /** An id out of a claim, for the ids that are not the subject. */
  UUID uuidClaim(JWTClaimsSet claims, String name) {
    try {
      return UUID.fromString(claim(claims, name, String.class));
    } catch (IllegalArgumentException notAnId) {
      throw new TokenException("a " + audience + " token whose " + name + " is not an id", notAnId);
    }
  }

  /** What signing produced: the compact token, and the two instants its claims fixed. */
  record Signed(String compact, Instant issuedAt, Instant expiresAt) {}
}
