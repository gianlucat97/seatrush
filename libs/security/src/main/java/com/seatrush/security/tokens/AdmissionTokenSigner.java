package com.seatrush.security.tokens;

import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.JWTClaimsSet.Builder;
import java.time.Clock;
import java.time.Duration;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;

/**
 * Mints and checks the admission token (README sections 6.2 and 13).
 *
 * <p>Both sides of it are used in production, and by two different services: queue-service signs
 * one when its admission job pops a buyer off the queue, and the gateway checks it on {@code POST
 * /holds}, {@code DELETE /holds/{id}} and {@code POST /orders}. They hold the same secret and
 * nothing else does.
 *
 * <p>A token and not a lookup, and that is the decision this class exists to make. The gateway
 * could ask queue-service whether a buyer is admitted, but then every purchase command in a flash
 * on-sale would be a synchronous call to the one service already doing the most work per second,
 * and the gateway would be down whenever it was. A signature costs a HMAC and no network at all.
 *
 * <p>What that buys is paid for with a token that cannot be revoked before it expires, which is why
 * its lifetime is minutes. Within those minutes the only thing it permits is holding seats that
 * {@code hold.lua} would have let this buyer hold anyway: one hold per user per event, and seats
 * that nobody else has. An admission is a key to the room and not to anything inside it.
 */
public final class AdmissionTokenSigner {

  static final String EVENT_CLAIM = "eventId";

  private final JwtCodec codec;

  /**
   * @param secret the HS256 secret of {@link TokenSecrets}, never the user one
   * @param ttl {@code seatrush.admission.token-ttl}, which also scores the slot in Redis
   */
  public AdmissionTokenSigner(SecretKeySpec secret, Clock clock, Duration ttl) {
    this.codec = new JwtCodec(secret, TokenAudience.ADMISSION, clock, ttl);
  }

  /** How long an admission lasts, which is the same window the active set holds the slot for. */
  public Duration ttl() {
    return codec.ttl();
  }

  /** An admission for a buyer the queue has just let through. */
  public IssuedToken<AdmissionToken> issue(UUID userId, UUID eventId) {
    JwtCodec.Signed signed =
        codec.sign(userId, new Builder().claim(EVENT_CLAIM, eventId.toString()));
    return new IssuedToken<>(
        signed.compact(),
        new AdmissionToken(userId, eventId, signed.issuedAt(), signed.expiresAt()));
  }

  /**
   * Reads an admission back.
   *
   * <p>It says nothing about which event the request was for: that is {@link
   * AdmissionToken#admits(UUID)}, and the gateway asks it separately because the event is in the
   * path and a valid admission for another on-sale is a different refusal from a bad signature.
   *
   * @throws TokenException if it was not signed by this secret for admissions, has expired, or is
   *     missing a claim
   */
  public AdmissionToken verify(String token) {
    JWTClaimsSet claims = codec.verify(token);
    return new AdmissionToken(
        codec.subject(claims),
        codec.uuidClaim(claims, EVENT_CLAIM),
        claims.getIssueTime().toInstant(),
        claims.getExpirationTime().toInstant());
  }
}
