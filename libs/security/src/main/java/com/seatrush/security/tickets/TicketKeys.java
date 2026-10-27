package com.seatrush.security.tickets;

import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * The Ed25519 key a ticket is signed with.
 *
 * <p>Configured in every environment that matters, because a signature is only worth anything if
 * the key outlives the pod that used it: two pods with different keys would mint tickets neither
 * could check, and a restart would invalidate everything issued before it.
 *
 * <p>Nothing here holds a default key. A signing key committed to a repository is not a key, and
 * README section 13 puts them in Kubernetes Secrets; what this offers instead is {@link
 * #generate()}, which a developer gets on a machine with nothing configured and which says so
 * loudly.
 */
public final class TicketKeys {

  private static final String ALGORITHM = "Ed25519";

  private TicketKeys() {}

  /** A fresh pair, for a local run with nothing configured and for the tests. */
  public static KeyPair generate() {
    try {
      return KeyPairGenerator.getInstance(ALGORITHM).generateKeyPair();
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("this JVM cannot generate an " + ALGORITHM + " key", e);
    }
  }

  /** The private key of a base64 PKCS#8 encoding, which is what {@code openssl} writes. */
  public static PrivateKey privateKey(String base64Pkcs8) {
    byte[] encoded = decode(base64Pkcs8, "private");
    try {
      return KeyFactory.getInstance(ALGORITHM).generatePrivate(new PKCS8EncodedKeySpec(encoded));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException(
          "the ticket signing key is not a PKCS#8 " + ALGORITHM + " key", e);
    }
  }

  /** The public key of a base64 X.509 encoding. */
  public static PublicKey publicKey(String base64X509) {
    byte[] encoded = decode(base64X509, "public");
    try {
      return KeyFactory.getInstance(ALGORITHM).generatePublic(new X509EncodedKeySpec(encoded));
    } catch (GeneralSecurityException e) {
      throw new IllegalArgumentException(
          "the ticket verifying key is not an X.509 " + ALGORITHM + " key", e);
    }
  }

  /** The base64 of an encoded key, which is how one is written into configuration. */
  public static String encode(byte[] encodedKey) {
    return Base64.getEncoder().encodeToString(encodedKey);
  }

  private static byte[] decode(String base64, String which) {
    try {
      return Base64.getDecoder().decode(base64.strip());
    } catch (IllegalArgumentException notBase64) {
      throw new IllegalArgumentException("the ticket " + which + " key is not base64", notBase64);
    }
  }
}
