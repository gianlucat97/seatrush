package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;
import com.seatrush.testsupport.time.MutableClock;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * The checks both token kinds inherit, tested once here rather than twice over the two signers.
 *
 * <p>Most of these are forgeries, and they are the point of the class: every one of them is a token
 * that a verifier doing slightly less work would accept.
 */
class JwtCodecTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Duration TTL = Duration.ofMinutes(10);

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final SecretKeySpec secret = TokenSecrets.key(TokenSecrets.generate());
  private final JwtCodec codec = new JwtCodec(secret, TokenAudience.ADMISSION, clock, TTL);

  @Test
  void readsBackWhatItSigned() {
    String token = codec.sign(USER_ID, new JWTClaimsSet.Builder()).compact();

    JWTClaimsSet claims = codec.verify(token);

    assertThat(claims.getSubject()).isEqualTo(USER_ID.toString());
    assertThat(claims.getIssuer()).isEqualTo(JwtCodec.ISSUER);
    assertThat(claims.getAudience()).containsExactly(TokenAudience.ADMISSION.claim());
  }

  /** Both instants are whole seconds, which is all a JWT can carry, and the caller is told so. */
  @Test
  void reportsTheInstantsTheTokenItselfWillCarry() {
    clock.setTo(Instant.parse("2026-06-01T10:00:00.750Z"));

    JwtCodec.Signed signed = codec.sign(USER_ID, new JWTClaimsSet.Builder());

    assertThat(signed.issuedAt()).isEqualTo(Instant.parse("2026-06-01T10:00:00Z"));
    assertThat(signed.expiresAt()).isEqualTo(Instant.parse("2026-06-01T10:10:00Z"));
    JWTClaimsSet claims = codec.verify(signed.compact());
    assertThat(claims.getIssueTime().toInstant())
        .as("so a slot scored by the reported instant dies with the token and not after it")
        .isEqualTo(signed.issuedAt());
    assertThat(claims.getExpirationTime().toInstant()).isEqualTo(signed.expiresAt());
  }

  /** The caller's instant, used as given rather than recomputed from the configured lifetime. */
  @Test
  void expiresWhenTheCallerSaidItShould() {
    Instant chosen = clock.instant().plusSeconds(42);

    JwtCodec.Signed signed = codec.signUntil(USER_ID, new JWTClaimsSet.Builder(), chosen);

    assertThat(signed.expiresAt()).isEqualTo(chosen);
    assertThat(codec.verify(signed.compact()).getExpirationTime().toInstant()).isEqualTo(chosen);
  }

  /** Whole seconds there too, so a slot scored by the reported instant dies with the token. */
  @Test
  void roundsTheCallersInstantToWhatAJwtCanCarry() {
    JwtCodec.Signed signed =
        codec.signUntil(
            USER_ID, new JWTClaimsSet.Builder(), Instant.parse("2026-06-01T10:07:30.900Z"));

    assertThat(signed.expiresAt()).isEqualTo(Instant.parse("2026-06-01T10:07:30Z"));
  }

  /**
   * A token born expired would be refused by its own verifier, so it is a bug and not an answer.
   */
  @Test
  void refusesToMintATokenThatHasAlreadyExpired() {
    assertThatThrownBy(() -> codec.signUntil(USER_ID, new JWTClaimsSet.Builder(), clock.instant()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot expire at");
    assertThatThrownBy(
            () ->
                codec.signUntil(
                    USER_ID, new JWTClaimsSet.Builder(), clock.instant().minusSeconds(1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot expire at");
  }

  @Test
  void acceptsATokenUntilTheInstantItExpires() {
    String token = codec.sign(USER_ID, new JWTClaimsSet.Builder()).compact();

    clock.advanceBy(TTL.minusSeconds(1));
    assertThatCode(() -> codec.verify(token)).doesNotThrowAnyException();

    clock.advanceBy(Duration.ofSeconds(1));
    assertThatThrownBy(() -> codec.verify(token))
        .as("expiry is the end of the window and not a point inside it")
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("expired at");
  }

  /**
   * No leeway, deliberately. A slot in the active set is already gone when the expiry passes, so a
   * token honoured a minute later would be a buyer nothing is counting.
   */
  @Test
  void givesNoLeewayForAClockRunningBehind() {
    String token = codec.sign(USER_ID, new JWTClaimsSet.Builder()).compact();

    clock.advanceBy(TTL.plusMillis(1));

    assertThatThrownBy(() -> codec.verify(token)).isInstanceOf(TokenException.class);
  }

  /**
   * A token minted by a pod whose clock runs ahead is accepted, because refusing it helps nobody.
   */
  @Test
  void acceptsATokenThatLooksFreshlyMintedFromTheFuture() {
    String token = codec.sign(USER_ID, new JWTClaimsSet.Builder()).compact();

    clock.advanceBy(Duration.ofSeconds(-5));

    assertThatCode(() -> codec.verify(token)).doesNotThrowAnyException();
  }

  @Test
  void refusesATokenAnotherSecretSigned() {
    JwtCodec somebodyElse =
        new JwtCodec(
            TokenSecrets.key(TokenSecrets.generate()), TokenAudience.ADMISSION, clock, TTL);
    String forged = somebodyElse.sign(USER_ID, new JWTClaimsSet.Builder()).compact();

    assertThatThrownBy(() -> codec.verify(forged))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("did not sign");
  }

  /** The claim somebody would want to change is the subject, so that is the one to try changing. */
  @Test
  void refusesATokenWhoseClaimsWereEdited() {
    String token = codec.sign(USER_ID, new JWTClaimsSet.Builder()).compact();
    String[] parts = token.split("\\.");
    String edited =
        new String(Base64.getUrlDecoder().decode(parts[1]))
            .replace(USER_ID.toString(), "0199c5f0-9999-7000-8000-00000000a999");

    String tampered =
        parts[0]
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(edited.getBytes())
            + "."
            + parts[2];

    assertThatThrownBy(() -> codec.verify(tampered))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("did not sign");
  }

  /**
   * The famous forgery: drop the signature and declare the token unsigned. It is refused before the
   * algorithm is even looked at, because an empty signature is not a signed JWT.
   */
  @Test
  void refusesATokenThatClaimsToNeedNoSignature() {
    String unsigned = new PlainJWT(claims(TokenAudience.ADMISSION)).serialize();

    assertThatThrownBy(() -> codec.verify(unsigned))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("not a signed JWT");
  }

  /**
   * The quieter forgery, and the reason the algorithm is compared to a constant. This token is
   * signed, with the real secret, over the right claims; only the algorithm in its header differs,
   * and an HMAC verifier left to read the algorithm out of the token would accept it.
   */
  @Test
  void refusesATokenSignedWithAnotherHmacAlgorithm() throws Exception {
    byte[] longEnoughForHs512 = new byte[64];
    new SecureRandom().nextBytes(longEnoughForHs512);
    SecretKeySpec sharedSecret =
        TokenSecrets.key(Base64.getEncoder().encodeToString(longEnoughForHs512));
    JwtCodec hs256 = new JwtCodec(sharedSecret, TokenAudience.ADMISSION, clock, TTL);

    SignedJWT hs512 =
        new SignedJWT(new JWSHeader(JWSAlgorithm.HS512), claims(TokenAudience.ADMISSION));
    hs512.sign(new MACSigner(sharedSecret));

    assertThatThrownBy(() -> hs256.verify(hs512.serialize()))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("signed with HS512 rather than HS256");
  }

  /** The second lock of {@link TokenAudience}: one secret, and still not one token. */
  @Test
  void refusesATokenOfTheOtherKindSignedWithTheSameSecret() {
    JwtCodec users = new JwtCodec(secret, TokenAudience.USER, clock, TTL);
    String userToken = users.sign(USER_ID, new JWTClaimsSet.Builder()).compact();

    assertThatThrownBy(() -> codec.verify(userToken))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("presented as a ADMISSION token");
  }

  @Test
  void refusesATokenFromAnotherSystem() throws Exception {
    SignedJWT elsewhere =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer("some-other-platform")
                .audience(TokenAudience.ADMISSION.claim())
                .subject(USER_ID.toString())
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plus(TTL)))
                .build());
    elsewhere.sign(new MACSigner(secret));

    assertThatThrownBy(() -> codec.verify(elsewhere.serialize()))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("issued by some-other-platform");
  }

  @Test
  void refusesATokenWithNoExpiry() throws Exception {
    SignedJWT forever =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(JwtCodec.ISSUER)
                .audience(TokenAudience.ADMISSION.claim())
                .subject(USER_ID.toString())
                .issueTime(Date.from(clock.instant()))
                .build());
    forever.sign(new MACSigner(secret));

    assertThatThrownBy(() -> codec.verify(forever.serialize()))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("never expires");
  }

  @Test
  void refusesSomethingThatIsNotATokenAtAll() {
    assertThatThrownBy(() -> codec.verify("hello"))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("not a signed JWT");
  }

  @Test
  void refusesASubjectThatIsNotAUserId() throws Exception {
    SignedJWT notAnId =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(JwtCodec.ISSUER)
                .audience(TokenAudience.ADMISSION.claim())
                .subject("admin")
                .issueTime(Date.from(clock.instant()))
                .expirationTime(Date.from(clock.instant().plus(TTL)))
                .build());
    notAnId.sign(new MACSigner(secret));

    assertThatThrownBy(() -> codec.subject(codec.verify(notAnId.serialize())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("subject is not a user id");
  }

  @Test
  void refusesALifetimeThatIsNotOne() {
    assertThatThrownBy(() -> new JwtCodec(secret, TokenAudience.USER, clock, Duration.ZERO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
    assertThatThrownBy(
            () -> new JwtCodec(secret, TokenAudience.USER, clock, Duration.ofMinutes(-1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be positive");
  }

  private JWTClaimsSet claims(TokenAudience audience) {
    return new JWTClaimsSet.Builder()
        .issuer(JwtCodec.ISSUER)
        .audience(audience.claim())
        .subject(USER_ID.toString())
        .issueTime(Date.from(clock.instant()))
        .expirationTime(Date.from(clock.instant().plus(TTL)))
        .build();
  }
}
