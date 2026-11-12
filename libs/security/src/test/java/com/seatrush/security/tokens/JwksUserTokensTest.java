package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nimbusds.jwt.JWTClaimsSet;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reading a user identity that somebody else signed (README section 13).
 *
 * <p>Against a real key set on a real port, for the reason {@link JwksServer} gives: the failures
 * of JWT verification live in the parsing and the key selection, and staging those needs two halves
 * of a key pair and a URL that publishes one of them.
 *
 * <p>Most of this class is refusals, and that is the right shape for a test of a verifier. The one
 * acceptance is the easy half; what matters is that nothing else gets through, because every one of
 * these would be somebody else's token accepted as a SeatRush identity.
 */
class JwksUserTokensTest {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final String ISSUER = "https://login.example.test/";
  private static final String AUDIENCE = "seatrush";

  private final MutableClock clock = MutableClock.at(NOW);
  private JwksServer provider;
  private JwksUserTokens tokens;

  @BeforeEach
  void anIdentityProvider() {
    provider = new JwksServer();
    tokens = verifierFor(provider.jwksUri());
  }

  @AfterEach
  void stopTheProvider() {
    provider.close();
  }

  @Test
  void readsAnIdentityOutOfATokenTheProviderSigned() {
    UserToken user = tokens.verify(provider.sign(claims().build()));

    assertThat(user.username()).isEqualTo("ada");
    assertThat(user.roles()).isEmpty();
    assertThat(user.admin()).isFalse();
    assertThat(user.expiresAt()).isEqualTo(NOW.plus(Duration.ofHours(1)));
  }

  /**
   * The same person is always the same buyer.
   *
   * <p>The one property the whole system keys on: a buyer who reloads the page must not lose the
   * hold, the order and the tickets they just paid for.
   */
  @Test
  void derivesTheSameUserIdFromTheSameSubjectEveryTime() {
    UserToken first = tokens.verify(provider.sign(claims().build()));
    clock.advanceBy(Duration.ofMinutes(5));
    UserToken second = tokens.verify(provider.sign(claims().build()));

    assertThat(second.userId()).isEqualTo(first.userId());
  }

  @Test
  void derivesADifferentUserIdForADifferentSubject() {
    UserToken ada = tokens.verify(provider.sign(claims().build()));
    UserToken grace = tokens.verify(provider.sign(claims().subject("auth0|grace").build()));

    assertThat(grace.userId()).isNotEqualTo(ada.userId());
  }

  /**
   * A provider's subject is whatever the provider chose, and it becomes a UUID regardless.
   *
   * <p>Which is the reason the id is derived rather than parsed: {@code X-User-Id} is what every
   * service behind the gateway keys its rows on, and no identity provider promises a UUID.
   */
  @Test
  void turnsASubjectThatIsNotAUuidIntoOne() {
    UserToken user = tokens.verify(provider.sign(claims().subject("12345").build()));

    assertThat(user.userId()).isNotNull();
  }

  /** Two providers cannot collide on one subject, because the issuer is part of what is hashed. */
  @Test
  void derivesADifferentUserIdForTheSameSubjectFromAnotherIssuer() {
    JwksUserTokens elsewhere =
        new JwksUserTokens(
            provider.jwksUri(),
            "https://other.example.test/",
            AUDIENCE,
            "preferred_username",
            "roles",
            Duration.ofMinutes(15),
            clock);

    UserToken here = tokens.verify(provider.sign(claims().build()));
    UserToken there =
        elsewhere.verify(provider.sign(claims().issuer("https://other.example.test/").build()));

    assertThat(there.userId()).isNotEqualTo(here.userId());
  }

  @Test
  void readsTheRoleTheAdminEndpointsDemand() {
    UserToken user =
        tokens.verify(provider.sign(claims().claim("roles", List.of("ADMIN")).build()));

    assertThat(user.admin()).isTrue();
  }

  /** Some providers write one space-delimited string rather than a list. */
  @Test
  void readsRolesWrittenAsOneString() {
    UserToken user = tokens.verify(provider.sign(claims().claim("roles", "buyer ADMIN").build()));

    assertThat(user.roles()).contains("ADMIN");
    assertThat(user.admin()).isTrue();
  }

  /**
   * A roles claim of a shape this build cannot read is a refusal, not an empty set.
   *
   * <p>Because the one question anybody asks of these is whether the caller is an administrator,
   * and answering "no" to a token that said something unreadable about roles is a guess.
   */
  @Test
  void refusesATokenWhoseRolesAreNeitherAListNorAString() {
    assertThatThrownBy(() -> tokens.verify(provider.sign(claims().claim("roles", 7).build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("roles");
  }

  @Test
  void hasNoRolesWhenTheProviderWritesNone() {
    assertThat(tokens.verify(provider.sign(claims().build())).roles()).isEmpty();
  }

  /** A provider that publishes no display name is a buyer, not a bad token. */
  @Test
  void fallsBackToTheSubjectWhenThereIsNoDisplayName() {
    UserToken user =
        tokens.verify(provider.sign(claims().claim("preferred_username", null).build()));

    assertThat(user.username()).isEqualTo("auth0|ada");
  }

  @Test
  void readsTheDisplayNameOutOfTheClaimThisDeploymentNames() {
    JwksUserTokens named =
        new JwksUserTokens(
            provider.jwksUri(), ISSUER, AUDIENCE, "name", "roles", Duration.ofMinutes(15), clock);

    UserToken user = named.verify(provider.sign(claims().claim("name", "Ada L").build()));

    assertThat(user.username()).isEqualTo("Ada L");
  }

  // The refusals.

  /**
   * The algorithm is compared to a constant and never read out of the token.
   *
   * <p>Signed by the key the provider really publishes, so a verifier that trusted the header would
   * accept it. This is the alg-confusion family, of which the sharpest version with a JWKS in play
   * is an HS256 token signed with the published public key as its secret.
   */
  @Test
  void refusesATokenSignedWithAnotherAlgorithm() {
    assertThatThrownBy(() -> tokens.verify(provider.signWithAnotherAlgorithm(claims().build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("RS256");
  }

  @Test
  void refusesATokenSignedWithAKeyTheProviderDoesNotPublish() {
    assertThatThrownBy(() -> tokens.verify(provider.signWithAnUnpublishedKey(claims().build())))
        .isInstanceOf(TokenException.class);
  }

  @Test
  void refusesATokenFromAnotherIssuer() {
    assertThatThrownBy(
            () -> tokens.verify(provider.sign(claims().issuer("https://evil.test/").build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("evil.test");
  }

  /**
   * The refusal a signature check alone cannot make.
   *
   * <p>A provider signs tokens for every application it serves out of one key set, so without the
   * audience a token minted for a different one of them would be a valid identity here.
   */
  @Test
  void refusesATokenTheProviderMintedForAnotherApplication() {
    assertThatThrownBy(
            () -> tokens.verify(provider.sign(claims().audience("some-other-app").build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("some-other-app");
  }

  @Test
  void refusesATokenWithNoAudienceAtAll() {
    assertThatThrownBy(() -> tokens.verify(provider.sign(claims().audience((String) null).build())))
        .isInstanceOf(TokenException.class);
  }

  @Test
  void refusesAnExpiredToken() {
    String token = provider.sign(claims().build());

    clock.advanceBy(Duration.ofHours(1));

    assertThatThrownBy(() -> tokens.verify(token))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("expired");
  }

  /** No tolerance for clock skew, matching the HS256 side. */
  @Test
  void refusesATokenThatExpiredOneSecondAgo() {
    String token = provider.sign(claims().expirationTime(Date.from(NOW.plusSeconds(1))).build());

    clock.advanceBy(Duration.ofSeconds(1));

    assertThatThrownBy(() -> tokens.verify(token)).isInstanceOf(TokenException.class);
  }

  @Test
  void refusesATokenThatNeverExpires() {
    assertThatThrownBy(() -> tokens.verify(provider.sign(claims().expirationTime(null).build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("never expires");
  }

  /** Unlike the tokens this system mints, one from a provider genuinely may not be valid yet. */
  @Test
  void refusesATokenThatIsNotValidYet() {
    String token =
        provider.sign(claims().notBeforeTime(Date.from(NOW.plus(Duration.ofMinutes(5)))).build());

    assertThatThrownBy(() -> tokens.verify(token))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("not valid before");
  }

  @Test
  void acceptsATokenOnceItsNotBeforeHasPassed() {
    String token =
        provider.sign(claims().notBeforeTime(Date.from(NOW.plus(Duration.ofMinutes(5)))).build());

    clock.advanceBy(Duration.ofMinutes(5));

    assertThat(tokens.verify(token).username()).isEqualTo("ada");
  }

  @Test
  void refusesATokenWithNoSubject() {
    assertThatThrownBy(() -> tokens.verify(provider.sign(claims().subject(null).build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("no subject");
  }

  @Test
  void refusesSomethingThatIsNotAJwtAtAll() {
    assertThatThrownBy(() -> tokens.verify("not-a-token"))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("not a signed JWT");
  }

  /**
   * A provider that cannot be reached is a refusal, not a request let through.
   *
   * <p>A gateway that accepted an identity because it could not check the signature would be no
   * gateway at all. It is logged differently, because it is not a token the caller got wrong.
   */
  @Test
  void refusesEveryTokenWhileTheKeySetCannotBeRead() {
    JwksUserTokens offline = verifierFor(JwksServer.unreachableJwksUri());

    assertThatThrownBy(() -> offline.verify(provider.sign(claims().build())))
        .isInstanceOf(TokenException.class)
        .hasMessageContaining("key set");
  }

  // The key set itself.

  /** Keys are fetched once and reused, not fetched per request. */
  @Test
  void fetchesTheKeySetOnceForManyTokens() {
    for (int token = 0; token < 5; token++) {
      tokens.verify(provider.sign(claims().build()));
    }

    assertThat(provider.fetches()).isEqualTo(1);
  }

  /**
   * A forged token cannot turn into a stream of requests to the provider.
   *
   * <p>Without the rate limit, a token naming a key id that does not exist would be a way to make
   * the gateway attack its own identity provider: every one of them is a cache miss.
   */
  @Test
  void doesNotRefetchTheKeySetForEveryTokenNamingAnUnknownKey() {
    tokens.verify(provider.sign(claims().build()));
    int afterTheFirstFetch = provider.fetches();

    for (int attempt = 0; attempt < 10; attempt++) {
      assertThatThrownBy(() -> tokens.verify(provider.signWithAnUnpublishedKey(claims().build())))
          .isInstanceOf(TokenException.class);
    }

    assertThat(provider.fetches()).isLessThanOrEqualTo(afterTheFirstFetch + 1);
  }

  // Construction.

  @Test
  void refusesToBeBuiltWithoutTheThreeFactsThatMakeATokenMeanThisDeployment() {
    assertThatThrownBy(() -> verifierWith(null, AUDIENCE))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("issuer");
    assertThatThrownBy(() -> verifierWith(ISSUER, "  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("audience");
  }

  @Test
  void refusesAJwksEndpointThatIsNotAnHttpUrl() {
    assertThatThrownBy(() -> verifierFor(java.net.URI.create("file:///etc/keys.json")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("http");
  }

  private JwksUserTokens verifierFor(java.net.URI jwksUri) {
    return new JwksUserTokens(
        jwksUri, ISSUER, AUDIENCE, "preferred_username", "roles", Duration.ofMinutes(15), clock);
  }

  private JwksUserTokens verifierWith(String issuer, String audience) {
    return new JwksUserTokens(
        provider.jwksUri(),
        issuer,
        audience,
        "preferred_username",
        "roles",
        Duration.ofMinutes(15),
        clock);
  }

  /** A token the provider would mint for an ordinary buyer, which every test then bends. */
  private JWTClaimsSet.Builder claims() {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject("auth0|ada")
        .claim("preferred_username", "ada")
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plus(Duration.ofHours(1))));
  }
}
