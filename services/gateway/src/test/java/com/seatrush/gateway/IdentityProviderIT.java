package com.seatrush.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.seatrush.gateway.adapters.in.web.AdmissionFilter;
import com.seatrush.gateway.adapters.in.web.UserAuthenticationFilter;
import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.testsupport.time.MutableClock;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The gateway in the {@code k8s} profile: a real edge in front of an identity provider it does not
 * own (README sections 13 and 22).
 *
 * <p>Everything here is a question about a request that crossed a wire, which is why this is an
 * integration test and not a unit one. Whether an RS256 token from somebody else's provider gets
 * through, what identity the service behind the gateway was handed for it, whether the admission of
 * ADR-0014 still works when the user token is no longer this system's, and whether the demo login
 * is really gone. The first and the last of those are the halves of this profile that were a
 * sentence in a document until now.
 *
 * <p>The provider is a real HTTP server with a real RSA key pair, because a mocked key source would
 * test the mock: the failures of this path live in the key selection and the claim checks.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("k8s")
class IdentityProviderIT {

  private static final Instant NOW = Instant.parse("2026-06-01T10:00:00Z");
  private static final String ISSUER = "https://login.example.test/";
  private static final String AUDIENCE = "seatrush";
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000d001");

  private static final IdentityProvider PROVIDER = new IdentityProvider();
  private static final StubService INVENTORY = new StubService("inventory");
  private static final StubService QUEUE = new StubService("queue");
  private static final StubService ORDERS = new StubService("order");
  private static final StubService REALTIME = new StubService("realtime");

  @Autowired private WebTestClient client;
  @Autowired private AdmissionTokenSigner admissions;
  @Autowired private MutableClock clock;
  @Autowired private org.springframework.context.ApplicationContext context;

  @DynamicPropertySource
  static void deployment(DynamicPropertyRegistry registry) {
    registry.add("seatrush.auth.jwks.uri", PROVIDER::jwksUri);
    registry.add("seatrush.auth.jwks.issuer", () -> ISSUER);
    registry.add("seatrush.auth.jwks.audience", () -> AUDIENCE);
    registry.add("seatrush.gateway.inventory", INVENTORY::url);
    registry.add("seatrush.gateway.queue", QUEUE::url);
    registry.add("seatrush.gateway.order", ORDERS::url);
    registry.add("seatrush.gateway.realtime", REALTIME::url);
    // The limits of README section 13 need a Redis this test has no business starting: what is
    // under test here is which identities get through, and RateLimitIT is where buckets live.
    registry.add("seatrush.ratelimit.enabled", () -> false);
    // The admission secret is still this system's, and still HS256: ADR-0014 is about a token
    // queue-service mints for the gateway, and nothing about an external provider changes it.
    registry.add(
        "seatrush.admission.secret", () -> "c2VhdHJ1c2gtYWRtaXNzaW9uLXNlY3JldC1mb3ItdGVzdHM=");
  }

  @AfterAll
  static void stopEverything() {
    PROVIDER.close();
    INVENTORY.close();
    QUEUE.close();
    ORDERS.close();
    REALTIME.close();
  }

  @BeforeEach
  void aFreshMinute() {
    clock.setTo(NOW);
    INVENTORY.forgetCalls();
    QUEUE.forgetCalls();
    ORDERS.forgetCalls();
    REALTIME.forgetCalls();
  }

  /**
   * The gateway holds nothing that could mint an identity.
   *
   * <p>The security property of this profile, and the reason the authentication filter was changed
   * to depend on an interface that cannot sign. A validator holding a signing key is a validator
   * that can forge what it validates.
   */
  @Test
  void holdsNoKeyThatCouldMintAUserIdentity() {
    assertThat(context.getBeanNamesForType(UserTokenSigner.class)).isEmpty();
  }

  @Test
  void letsThroughATokenTheProviderSigned() {
    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(claims()))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(INVENTORY.lastCall()).isNotNull();
  }

  /**
   * The identity a service behind the gateway is handed is a UUID, whatever the provider's subject
   * was.
   *
   * <p>Which is the whole reason the id is derived rather than parsed: every row downstream is
   * keyed on it.
   */
  @Test
  void handsDownstreamAStableUuidForAProviderSubjectThatIsNotOne() {
    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(claims()))
        .exchange()
        .expectStatus()
        .isOk();
    String first = INVENTORY.lastCall().userId();

    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(claims()))
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(UUID.fromString(first)).isNotNull();
    assertThat(INVENTORY.lastCall().userId()).isEqualTo(first);
  }

  @Test
  void refusesATokenThisDeploymentsProviderDidNotSign() {
    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.signWithAnUnpublishedKey(claims()))
        .exchange()
        .expectStatus()
        .isUnauthorized()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("UNAUTHENTICATED");
  }

  @Test
  void refusesATokenMintedForAnotherApplicationOfTheSameProvider() {
    JWTClaimsSet other = new JWTClaimsSet.Builder(claims()).audience("some-other-app").build();

    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(other))
        .exchange()
        .expectStatus()
        .isUnauthorized();
  }

  /** The role claim still decides the admin endpoints, now read out of somebody else's token. */
  @Test
  void refusesAnAdminEndpointToAProviderTokenWithNoRole() {
    client
        .post()
        .uri("/api/admin/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(claims()))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("FORBIDDEN");
  }

  @Test
  void letsAProviderTokenCarryingTheRoleThroughToTheAdminEndpoints() {
    JWTClaimsSet operator =
        new JWTClaimsSet.Builder(claims()).claim("roles", List.of("ADMIN")).build();

    client
        .post()
        .uri("/api/admin/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(operator))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{}")
        .exchange()
        .expectStatus()
        .isOk();
  }

  /**
   * The admission of ADR-0014 is unchanged by any of this.
   *
   * <p>It is a token queue-service mints for the gateway with a secret only those two hold, so an
   * external identity provider has nothing to do with it. Worth asserting precisely because the two
   * tokens now come from different places and arrive on the same request.
   */
  @Test
  void stillDemandsAnAdmissionOnAPurchaseCommand() {
    client
        .post()
        .uri("/api/events/" + EVENT_ID + "/holds")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(claims()))
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"seatIds\":[\"A-1-1\"]}")
        .exchange()
        .expectStatus()
        .isForbidden()
        .expectBody()
        .jsonPath("$.code")
        .isEqualTo("ADMISSION_REQUIRED");
  }

  /**
   * And accepts one, for the buyer the provider's token identifies.
   *
   * <p>The admission is bound to a user id, and that id is now derived from the provider's subject,
   * so this is the one test that proves the two halves agree on who the caller is.
   */
  @Test
  void acceptsAnAdmissionIssuedToTheIdentityTheProviderNamed() {
    String token = PROVIDER.sign(claims());
    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .exchange()
        .expectStatus()
        .isOk();
    UUID buyer = UUID.fromString(INVENTORY.lastCall().userId());

    client
        .post()
        .uri("/api/events/" + EVENT_ID + "/holds")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
        .header(AdmissionFilter.ADMISSION_HEADER, admissions.issue(buyer, EVENT_ID).compact())
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"seatIds\":[\"A-1-1\"]}")
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(INVENTORY.lastCall().userId()).isEqualTo(buyer.toString());
  }

  /** The demo login of README section 12.1 is not in this profile at all. */
  @Test
  void hasNoDemoLogin() {
    client
        .post()
        .uri("/api/auth/demo-login")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"username\":\"ada\",\"admin\":true}")
        .exchange()
        .expectStatus()
        .isNotFound();
  }

  /** Whatever a client claims about its own identity is still removed before forwarding. */
  @Test
  void stillDropsTheIdentityHeaderAClientSent() {
    client
        .get()
        .uri("/api/events")
        .header(HttpHeaders.AUTHORIZATION, "Bearer " + PROVIDER.sign(claims()))
        .header(UserAuthenticationFilter.USER_ID_HEADER, UUID.randomUUID().toString())
        .exchange()
        .expectStatus()
        .isOk();

    assertThat(UUID.fromString(INVENTORY.lastCall().userId())).isNotNull();
    assertThat(INVENTORY.lastCall().userId()).isNotEqualTo("00000000-0000-0000-0000-000000000000");
  }

  private static JWTClaimsSet claims() {
    return new JWTClaimsSet.Builder()
        .issuer(ISSUER)
        .audience(AUDIENCE)
        .subject("auth0|ada")
        .claim("preferred_username", "ada")
        .issueTime(Date.from(NOW))
        .expirationTime(Date.from(NOW.plus(Duration.ofHours(1))))
        .build();
  }

  /** The clock both the admission signer and the token verifier read. */
  @TestConfiguration
  static class MovableTime {

    @Bean
    Clock clock() {
      return MutableClock.at(NOW);
    }

    @Bean
    MutableClock mutableClock(Clock clock) {
      return (MutableClock) clock;
    }
  }

  /**
   * An identity provider: a key set at a URL, and the private half to sign with.
   *
   * <p>Deliberately a copy of the one in {@code libs/security}'s tests rather than a shared
   * fixture. That module is a library and this is a service, and a test fixture promoted into the
   * main artifact of a library so that one service can reuse it is a dependency pointing the wrong
   * way.
   */
  private static final class IdentityProvider implements AutoCloseable {

    private final HttpServer server;
    private final RSAKey key;
    private final RSAKey unpublishedKey;

    IdentityProvider() {
      try {
        this.key = new RSAKeyGenerator(2048).keyID("published").keyUse(KeyUse.SIGNATURE).generate();
        this.unpublishedKey = new RSAKeyGenerator(2048).keyID("unpublished").generate();
        this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
      } catch (JOSEException | IOException e) {
        throw new IllegalStateException("could not stand up an identity provider", e);
      }

      byte[] document =
          new JWKSet(List.of(key.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
      server.createContext(
          "/jwks.json",
          exchange -> {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, document.length);
            try (OutputStream body = exchange.getResponseBody()) {
              body.write(document);
            }
          });
      server.start();
    }

    String jwksUri() {
      return "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json";
    }

    String sign(JWTClaimsSet claims) {
      return sign(claims, key);
    }

    String signWithAnUnpublishedKey(JWTClaimsSet claims) {
      return sign(claims, unpublishedKey);
    }

    private static String sign(JWTClaimsSet claims, RSAKey signingKey) {
      SignedJWT jwt =
          new SignedJWT(
              new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.getKeyID()).build(),
              claims);
      try {
        jwt.sign(new RSASSASigner(signingKey));
      } catch (JOSEException e) {
        throw new IllegalStateException("could not sign a test token", e);
      }
      return jwt.serialize();
    }

    @Override
    public void close() {
      server.stop(0);
    }
  }
}
