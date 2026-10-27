package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/**
 * What the gateway demands on a purchase command (README sections 6.2 and 13).
 *
 * <p>This is the token that stands between a bot and a seat, so the tests that matter are the ones
 * that try to get in with something close enough to the real thing.
 */
class AdmissionTokenSignerTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final UUID ANOTHER_EVENT = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final Duration TTL = Duration.ofMinutes(10);

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final SecretKeySpec secret = TokenSecrets.key(TokenSecrets.generate());
  private final AdmissionTokenSigner signer = new AdmissionTokenSigner(secret, clock, TTL);

  @Test
  void readsBackWhoWasAdmittedAndForWhat() {
    IssuedToken<AdmissionToken> issued = signer.issue(USER_ID, EVENT_ID);

    assertThat(signer.verify(issued.compact())).isEqualTo(issued.claims());
    assertThat(issued.claims().userId()).isEqualTo(USER_ID);
    assertThat(issued.claims().eventId()).isEqualTo(EVENT_ID);
  }

  /**
   * The expiry the queue hands the client is the expiry inside the token, because the same instant
   * scores the buyer's slot in the active set. A token outliving its slot would be a buyer in the
   * purchase area that nothing is counting.
   */
  @Test
  void expiresWhenTheSlotItWasIssuedWithExpires() {
    IssuedToken<AdmissionToken> issued = signer.issue(USER_ID, EVENT_ID);

    assertThat(issued.claims().expiresAt()).isEqualTo(clock.instant().plus(TTL));

    clock.setTo(issued.claims().expiresAt().minusSeconds(1));
    assertThatCode(() -> signer.verify(issued.compact())).doesNotThrowAnyException();

    clock.setTo(issued.claims().expiresAt());
    assertThatThrownBy(() -> signer.verify(issued.compact()))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("ADMISSION token that expired");
  }

  /**
   * What the admission job calls. The instant it gives is the one that scores the buyer's slot, so
   * the token and the slot are the same instant used twice rather than two readings of a clock.
   */
  @Test
  void endsExactlyWhenTheQueueSaidTheSlotDoes() {
    Instant slotExpiresAt = clock.instant().plusSeconds(90);

    IssuedToken<AdmissionToken> issued = signer.issueUntil(USER_ID, EVENT_ID, slotExpiresAt);

    assertThat(issued.claims().expiresAt()).isEqualTo(slotExpiresAt);
    assertThat(signer.verify(issued.compact()).expiresAt()).isEqualTo(slotExpiresAt);

    clock.setTo(slotExpiresAt);
    assertThatThrownBy(() -> signer.verify(issued.compact()))
        .as("the token dies with the slot and not a second after it")
        .isInstanceOf(TokenException.class);
  }

  @Test
  void refusesToMintAnAdmissionThatIsAlreadyOver() {
    assertThatThrownBy(() -> signer.issueUntil(USER_ID, EVENT_ID, clock.instant()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot expire at");
  }

  /**
   * The binding that makes a waiting room bound anything. Without the event in the token, an
   * admission earned by queueing for a quiet show would be a pass to every on-sale in the system.
   */
  @Test
  void admitsOneEventAndNoOther() {
    AdmissionToken admission = signer.verify(signer.issue(USER_ID, EVENT_ID).compact());

    assertThat(admission.admits(EVENT_ID)).isTrue();
    assertThat(admission.admits(ANOTHER_EVENT)).isFalse();
  }

  @Test
  void refusesAnAdmissionAnotherSecretSigned() {
    AdmissionTokenSigner elsewhere =
        new AdmissionTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, TTL);
    String forged = elsewhere.issue(USER_ID, EVENT_ID).compact();

    assertThatThrownBy(() -> signer.verify(forged))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("did not sign");
  }

  /**
   * The reason the two secrets are separate and the audiences are separate as well: a user token is
   * the one token every client already holds, so it is the obvious thing to try here.
   */
  @Test
  void refusesAUserTokenPresentedAsAnAdmission() {
    String login =
        new UserTokenSigner(secret, clock, TTL).issue(USER_ID, "ada", Set.of()).compact();

    assertThatThrownBy(() -> signer.verify(login))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("presented as a ADMISSION token");
  }

  @Test
  void refusesAnAdmissionForNoEventAtAll() throws Exception {
    Instant now = clock.instant();
    SignedJWT eventless =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(JwtCodec.ISSUER)
                .audience(TokenAudience.ADMISSION.claim())
                .subject(USER_ID.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TTL)))
                .build());
    eventless.sign(new MACSigner(secret));

    assertThatThrownBy(() -> signer.verify(eventless.serialize()))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("no String eventId claim");
  }

  @Test
  void refusesAnAdmissionForSomethingThatIsNotAnEvent() throws Exception {
    Instant now = clock.instant();
    SignedJWT notAnEvent =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            new JWTClaimsSet.Builder()
                .issuer(JwtCodec.ISSUER)
                .audience(TokenAudience.ADMISSION.claim())
                .subject(USER_ID.toString())
                .claim(AdmissionTokenSigner.EVENT_CLAIM, "*")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TTL)))
                .build());
    notAnEvent.sign(new MACSigner(secret));

    assertThatThrownBy(() -> signer.verify(notAnEvent.serialize()))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("eventId is not an id");
  }

  @Test
  void saysHowLongAnAdmissionLasts() {
    assertThat(signer.ttl()).isEqualTo(TTL);
  }
}
