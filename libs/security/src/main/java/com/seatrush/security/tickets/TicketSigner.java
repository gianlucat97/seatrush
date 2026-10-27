package com.seatrush.security.tickets;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Objects;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs a ticket, and checks one (README section 13).
 *
 * <p>Ed25519, because the thing being signed ends up in a QR code and is checked by a scanner at a
 * turnstile: the signature is 64 bytes, verification needs no network and no state, and the
 * algorithm has no parameters anybody can get wrong. An HMAC would be shorter still, but it would
 * mean every scanner holding the key that mints tickets.
 *
 * <p>The signed form carries the payload with it, which is what makes it verifiable offline. A
 * scanner decodes the bytes, checks them against the public key, and reads the seat out of the same
 * bytes it just verified. Nothing is looked up, so nothing can be looked up wrongly.
 *
 * <pre>{@code
 * v1.<base64url of the payload JSON>.<base64url of the signature>
 * }</pre>
 *
 * <p>The payload travels base64 encoded rather than re-serialised on the way back, because the
 * signature covers bytes and not meaning: re-encoding it to verify would make the check depend on
 * two sides agreeing about field order and whitespace, which is the failure mode canonicalisation
 * bugs live in.
 */
public final class TicketSigner {

  /** The one format this build writes, and the only one it reads. */
  static final String VERSION = "v1";

  private static final String ALGORITHM = "Ed25519";
  private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
  private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

  private final PrivateKey signingKey;
  private final PublicKey verifyingKey;
  private final JsonMapper payloads;

  public TicketSigner(PrivateKey signingKey, PublicKey verifyingKey) {
    this.signingKey = Objects.requireNonNull(signingKey, "signingKey must not be null");
    this.verifyingKey = Objects.requireNonNull(verifyingKey, "verifyingKey must not be null");
    this.payloads =
        JsonMapper.builder()
            .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  /** The signed form of a ticket, which is what goes in the database and on the QR code. */
  public String sign(TicketPayload payload) {
    Objects.requireNonNull(payload, "payload must not be null");
    byte[] bytes = payloads.writeValueAsString(payload).getBytes(StandardCharsets.UTF_8);
    return VERSION
        + "."
        + ENCODER.encodeToString(bytes)
        + "."
        + ENCODER.encodeToString(signatureOf(bytes));
  }

  /**
   * Reads a signed ticket back, refusing anything the key did not sign.
   *
   * @throws TicketSignatureException if the format is not one this build writes, the bytes are not
   *     a payload, or the signature does not belong to them
   */
  public TicketPayload verify(String signed) {
    Objects.requireNonNull(signed, "signed must not be null");
    String[] parts = signed.split("\\.", -1);
    if (parts.length != 3 || !VERSION.equals(parts[0])) {
      throw new TicketSignatureException("a ticket in a format this build does not write");
    }

    byte[] bytes;
    byte[] signature;
    try {
      bytes = DECODER.decode(parts[1]);
      signature = DECODER.decode(parts[2]);
    } catch (IllegalArgumentException notBase64) {
      throw new TicketSignatureException("a ticket that is not base64url", notBase64);
    }

    if (!isSignatureOf(bytes, signature)) {
      // Checked before the payload is read, so a forged ticket is never parsed at all.
      throw new TicketSignatureException("a ticket this key did not sign");
    }
    try {
      return payloads.readValue(new String(bytes, StandardCharsets.UTF_8), TicketPayload.class);
    } catch (JacksonException | IllegalArgumentException | NullPointerException notAPayload) {
      // Signed by the right key and still not readable, which means a newer build minted it.
      throw new TicketSignatureException("a signed ticket this build cannot read", notAPayload);
    }
  }

  private byte[] signatureOf(byte[] bytes) {
    try {
      Signature signature = Signature.getInstance(ALGORITHM);
      signature.initSign(signingKey);
      signature.update(bytes);
      return signature.sign();
    } catch (GeneralSecurityException e) {
      // The key was accepted when it was loaded, so a failure here is the JVM and not the input.
      throw new IllegalStateException("could not sign a ticket", e);
    }
  }

  private boolean isSignatureOf(byte[] bytes, byte[] signature) {
    try {
      Signature verifier = Signature.getInstance(ALGORITHM);
      verifier.initVerify(verifyingKey);
      verifier.update(bytes);
      return verifier.verify(signature);
    } catch (GeneralSecurityException malformed) {
      // A signature of the wrong length or shape, which is a refusal and not a failure.
      return false;
    }
  }
}
