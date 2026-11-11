package com.seatrush.security.tokens;

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
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * An identity provider, reduced to the two things one has to be for this test: a key set at a URL
 * and something that can sign with the other half of it.
 *
 * <p>A real server on a real port and real RSA keys, because what is under test is a verifier. A
 * mocked key source would test the mock, and the failures of JWT verification live in the parsing
 * and the key selection rather than in the cryptography: a wrong key id, an algorithm the selector
 * was not built for, a token signed by the wrong key. None of those can be staged without two
 * halves of a key pair and a URL that publishes one of them.
 *
 * <p>{@code com.sun.net.httpserver} rather than a library, because the whole of what is needed is
 * one path answering one JSON document, and this module deliberately depends on almost nothing.
 *
 * <p>It counts the requests it answers, which is how the caching and the rate limiting of {@link
 * JwksUserTokens} become assertions rather than claims.
 */
final class JwksServer implements AutoCloseable {

  private final HttpServer server;
  private final RSAKey key;
  private final RSAKey otherKey;
  private final AtomicInteger fetches = new AtomicInteger();

  JwksServer() {
    try {
      this.key =
          new RSAKeyGenerator(2048)
              .keyID("the-one-it-publishes")
              .keyUse(KeyUse.SIGNATURE)
              .generate();
      // Generated and never published, so a token signed with it is a token whose key the
      // provider does not know: the forged-signature case.
      this.otherKey = new RSAKeyGenerator(2048).keyID("the-one-it-does-not").generate();
      this.server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    } catch (JOSEException | IOException e) {
      throw new IllegalStateException("could not stand up a key set", e);
    }

    byte[] document =
        new JWKSet(List.of(key.toPublicJWK())).toString().getBytes(StandardCharsets.UTF_8);
    server.createContext(
        "/jwks.json",
        exchange -> {
          fetches.incrementAndGet();
          exchange.getResponseHeaders().set("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, document.length);
          try (OutputStream body = exchange.getResponseBody()) {
            body.write(document);
          }
        });
    server.start();
  }

  /** Where the keys are published, which is what a deployment configures. */
  URI jwksUri() {
    return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/jwks.json");
  }

  /** A URL nothing answers on, for the provider-is-down case. */
  static URI unreachableJwksUri() {
    return URI.create("http://127.0.0.1:1/jwks.json");
  }

  /** How many times the key set has been fetched since this server started. */
  int fetches() {
    return fetches.get();
  }

  /** A token the provider signed, from a claims set the caller composed. */
  String sign(JWTClaimsSet claims) {
    return sign(claims, key, JWSAlgorithm.RS256);
  }

  /** The same, signed with the key this provider does not publish. */
  String signWithAnUnpublishedKey(JWTClaimsSet claims) {
    return sign(claims, otherKey, JWSAlgorithm.RS256);
  }

  /**
   * The same claims, signed RS512 with the published key.
   *
   * <p>For the algorithm check. A verifier that read the algorithm out of the header would accept
   * this, because the key that signed it really is the key the provider publishes.
   */
  String signWithAnotherAlgorithm(JWTClaimsSet claims) {
    return sign(claims, key, JWSAlgorithm.RS512);
  }

  private static String sign(JWTClaimsSet claims, RSAKey signingKey, JWSAlgorithm algorithm) {
    SignedJWT jwt =
        new SignedJWT(
            new JWSHeader.Builder(algorithm).keyID(signingKey.getKeyID()).build(), claims);
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
