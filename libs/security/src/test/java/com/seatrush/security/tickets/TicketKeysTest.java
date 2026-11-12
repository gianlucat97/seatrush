package com.seatrush.security.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import org.junit.jupiter.api.Test;

class TicketKeysTest {

  private final KeyPair keys = TicketKeys.generate();

  /**
   * The JVM names the key algorithm after the family and not the curve, so a key generated for
   * Ed25519 reports EdDSA. Asserted as it is rather than as it reads, because a test that expected
   * the curve name would be asserting something no JDK says.
   */
  @Test
  void generatesAnEdDsaPairInTheEd25519Encodings() {
    assertThat(keys.getPrivate().getAlgorithm()).isEqualTo("EdDSA");
    assertThat(keys.getPrivate().getFormat()).isEqualTo("PKCS#8");
    assertThat(keys.getPrivate().getEncoded()).hasSize(48);
    assertThat(keys.getPublic().getFormat()).isEqualTo("X.509");
    assertThat(keys.getPublic().getEncoded()).hasSize(44);
  }

  /** A key written into configuration has to come back as the same key. */
  @Test
  void readsBackAKeyItEncoded() {
    String privateKey = TicketKeys.encode(keys.getPrivate().getEncoded());
    String publicKey = TicketKeys.encode(keys.getPublic().getEncoded());

    TicketSigner signer =
        new TicketSigner(TicketKeys.privateKey(privateKey), TicketKeys.publicKey(publicKey));

    assertThat(signer.verify(signer.sign(aPayload()))).isEqualTo(aPayload());
  }

  @Test
  void toleratesTheWhitespaceConfigurationPicksUp() {
    String padded = "  " + TicketKeys.encode(keys.getPrivate().getEncoded()) + "\n";

    assertThat(TicketKeys.privateKey(padded)).isEqualTo(keys.getPrivate());
  }

  @Test
  void refusesSomethingThatIsNotAKey() {
    assertThatThrownBy(() -> TicketKeys.privateKey("not base64!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not base64");
    assertThatThrownBy(() -> TicketKeys.publicKey("bm90IGEga2V5"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("X.509");
  }

  /** The two halves are not interchangeable, and saying so beats a signature that never checks. */
  @Test
  void refusesThePublicKeyWhereThePrivateOneBelongs() {
    String publicKey = TicketKeys.encode(keys.getPublic().getEncoded());

    assertThatThrownBy(() -> TicketKeys.privateKey(publicKey))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("PKCS#8");
  }

  private static TicketPayload aPayload() {
    return new TicketPayload(
        java.util.UUID.fromString("0199c5f0-7777-7000-8000-00000000f007"),
        java.util.UUID.fromString("0199c5f0-2222-7000-8000-00000000b002"),
        "A-1-1",
        java.time.Instant.parse("2026-06-01T10:00:00Z"));
  }
}
