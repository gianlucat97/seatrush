package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

/** What the gateway learns from a user JWT, and what it refuses to learn (README section 13). */
class UserTokenSignerTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final Duration TTL = Duration.ofHours(1);

  private final MutableClock clock = MutableClock.atArbitraryInstant();
  private final SecretKeySpec secret = TokenSecrets.key(TokenSecrets.generate());
  private final UserTokenSigner signer = new UserTokenSigner(secret, clock, TTL);

  @Test
  void readsBackWhoLoggedIn() {
    IssuedToken<UserToken> issued = signer.issue(USER_ID, "ada", Set.of());

    assertThat(signer.verify(issued.compact())).isEqualTo(issued.claims());
    assertThat(issued.claims().userId()).isEqualTo(USER_ID);
    assertThat(issued.claims().username()).isEqualTo("ada");
    assertThat(issued.claims().admin()).isFalse();
    assertThat(issued.claims().issuedAt()).isEqualTo(clock.instant());
    assertThat(issued.claims().expiresAt()).isEqualTo(clock.instant().plus(TTL));
  }

  /** The claim the admin endpoints of README section 12.2 branch on. */
  @Test
  void carriesTheAdminRole() {
    IssuedToken<UserToken> issued = signer.issue(USER_ID, "root", Set.of(UserToken.ADMIN));

    UserToken verified = signer.verify(issued.compact());

    assertThat(verified.admin()).isTrue();
    assertThat(verified.roles()).containsExactly("ADMIN");
  }

  /** An identity provider writing roles in another case still means the same roles. */
  @Test
  void readsARoleWhateverCaseItWasWrittenIn() {
    IssuedToken<UserToken> issued = signer.issue(USER_ID, "root", Set.of("admin"));

    assertThat(signer.verify(issued.compact()).admin()).isTrue();
  }

  /**
   * A token with no roles claim is an ordinary buyer and not a malformed token: an identity
   * provider has no reason to write an empty array, and a buyer is what nearly every endpoint
   * expects.
   */
  @Test
  void treatsATokenWithNoRolesClaimAsAnOrdinaryBuyer() throws Exception {
    String withoutRoles =
        signed(new JWTClaimsSet.Builder().claim(UserTokenSigner.USERNAME_CLAIM, "ada"));

    UserToken verified = signer.verify(withoutRoles);

    assertThat(verified.roles()).isEmpty();
    assertThat(verified.admin()).isFalse();
  }

  @Test
  void refusesATokenWhoseRolesAreNotNames() throws Exception {
    String oddRoles =
        signed(
            new JWTClaimsSet.Builder()
                .claim(UserTokenSigner.USERNAME_CLAIM, "ada")
                .claim(UserTokenSigner.ROLES_CLAIM, List.of(1, 2, 3)));

    assertThatThrownBy(() -> signer.verify(oddRoles))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("roles claim is not a list of names");
  }

  @Test
  void refusesATokenWithNobodyInIt() throws Exception {
    String nameless = signed(new JWTClaimsSet.Builder());

    assertThatThrownBy(() -> signer.verify(nameless))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("no String username claim");
  }

  @Test
  void refusesALoginThatHasExpired() {
    String token = signer.issue(USER_ID, "ada", Set.of()).compact();

    clock.advanceBy(TTL);

    assertThatThrownBy(() -> signer.verify(token))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("USER token that expired");
  }

  @Test
  void refusesATokenAnotherSecretSigned() {
    UserTokenSigner elsewhere =
        new UserTokenSigner(TokenSecrets.key(TokenSecrets.generate()), clock, TTL);
    String forged = elsewhere.issue(USER_ID, "ada", Set.of(UserToken.ADMIN)).compact();

    assertThatThrownBy(() -> signer.verify(forged))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("did not sign");
  }

  /**
   * The confusion worth being explicit about. An admission token says a buyer reached the front of
   * a queue; accepted as an identity it would be an unnamed user with whatever roles the reader
   * defaulted to.
   */
  @Test
  void refusesAnAdmissionTokenPresentedAsAnIdentity() {
    String admission =
        new AdmissionTokenSigner(secret, clock, Duration.ofMinutes(10))
            .issue(USER_ID, UUID.randomUUID())
            .compact();

    assertThatThrownBy(() -> signer.verify(admission))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("presented as a USER token");
  }

  @Test
  void saysHowLongALoginLasts() {
    assertThat(signer.ttl()).isEqualTo(TTL);
  }

  /**
   * A token of this signer's shape, signed with its secret, so only the claims under test differ.
   */
  private String signed(JWTClaimsSet.Builder claims) throws Exception {
    Instant now = clock.instant();
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader(JWSAlgorithm.HS256),
            claims
                .issuer(JwtCodec.ISSUER)
                .audience(TokenAudience.USER.claim())
                .subject(USER_ID.toString())
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plus(TTL)))
                .build());
    jwt.sign(new MACSigner(secret));
    return jwt.serialize();
  }
}
