package com.seatrush.security.tickets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * What a signature is for: a gate can tell a ticket this key issued from everything else, with no
 * network and no state (README section 13).
 */
class TicketSignerTest {

  private static final UUID TICKET_ID = UUID.fromString("0199c5f0-7777-7000-8000-00000000f007");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ISSUED_AT = Instant.parse("2026-06-01T10:00:00Z");

  private final KeyPair keys = TicketKeys.generate();
  private final TicketSigner signer = new TicketSigner(keys.getPrivate(), keys.getPublic());

  @Test
  void readsBackWhatItSigned() {
    TicketPayload payload = payload("A-1-1");

    TicketPayload verified = signer.verify(signer.sign(payload));

    assertThat(verified).isEqualTo(payload);
  }

  @Test
  void writesSomethingAQrCodeCanCarry() {
    String signed = signer.sign(payload("A-1-1"));

    assertThat(signed).startsWith("v1.");
    assertThat(signed.split("\\.")).hasSize(3);
    assertThat(signed)
        .as("base64url and dots only, so nothing has to be escaped on the way to a scanner")
        .matches("v1\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+");
  }

  /** The whole point: a scanner holding the public key alone can do this. */
  @Test
  void isCheckableWithThePublicKeyAlone() {
    String signed = signer.sign(payload("A-1-1"));
    TicketSigner gate =
        new TicketSigner(
            TicketKeys.generate().getPrivate(),
            TicketKeys.publicKey(TicketKeys.encode(keys.getPublic().getEncoded())));

    assertThat(gate.verify(signed).seatId()).isEqualTo("A-1-1");
  }

  @Test
  void refusesATicketAnotherKeySigned() {
    KeyPair somebodyElse = TicketKeys.generate();
    String forged =
        new TicketSigner(somebodyElse.getPrivate(), somebodyElse.getPublic())
            .sign(payload("A-1-1"));

    assertThatThrownBy(() -> signer.verify(forged))
        .isInstanceOf(TicketSignatureException.class)
        .hasMessageContaining("did not sign");
  }

  /** The seat is the thing somebody would want to change, so it is the thing to try changing. */
  @Test
  void refusesATicketWhoseSeatWasEdited() {
    String signed = signer.sign(payload("A-1-1"));
    String[] parts = signed.split("\\.");
    String tampered = new String(Base64.getUrlDecoder().decode(parts[1])).replace("A-1-1", "A-1-2");
    String reassembled =
        parts[0]
            + "."
            + Base64.getUrlEncoder().withoutPadding().encodeToString(tampered.getBytes())
            + "."
            + parts[2];

    assertThatThrownBy(() -> signer.verify(reassembled))
        .isInstanceOf(TicketSignatureException.class)
        .hasMessageContaining("did not sign");
  }

  @Test
  void refusesASignatureFromAnotherTicketOfTheSameKey() {
    String first = signer.sign(payload("A-1-1"));
    String second = signer.sign(payload("A-1-2"));
    String swapped =
        first.split("\\.")[0] + "." + first.split("\\.")[1] + "." + second.split("\\.")[2];

    assertThatThrownBy(() -> signer.verify(swapped)).isInstanceOf(TicketSignatureException.class);
  }

  @Test
  void refusesSomethingThatIsNotATicketAtAll() {
    assertThatThrownBy(() -> signer.verify("")).isInstanceOf(TicketSignatureException.class);
    assertThatThrownBy(() -> signer.verify("v1.only-two-parts"))
        .isInstanceOf(TicketSignatureException.class);
    assertThatThrownBy(() -> signer.verify("v1.a.b.c"))
        .isInstanceOf(TicketSignatureException.class);
    assertThatThrownBy(
            () -> signer.verify("v2." + signer.sign(payload("A-1-1")).split("\\.")[1] + ".x"))
        .as("a version this build does not write is refused before anything is decoded")
        .isInstanceOf(TicketSignatureException.class)
        .hasMessageContaining("does not write");
  }

  @Test
  void refusesSomethingThatIsNotBase64() {
    assertThatThrownBy(() -> signer.verify("v1.not base64!.also not"))
        .isInstanceOf(TicketSignatureException.class)
        .hasMessageContaining("base64url");
  }

  /** A truncated QR code reads as a signature of the wrong length, which is a refusal. */
  @Test
  void refusesATruncatedSignature() {
    String signed = signer.sign(payload("A-1-1"));
    String[] parts = signed.split("\\.");
    String truncated = parts[0] + "." + parts[1] + "." + parts[2].substring(0, 20);

    assertThatThrownBy(() -> signer.verify(truncated)).isInstanceOf(TicketSignatureException.class);
  }

  /** Ed25519 is deterministic, which is what makes a signed ticket stable in a column. */
  @Test
  void signsOneTicketTheSameWayEveryTime() {
    TicketPayload payload = payload("A-1-1");

    assertThat(signer.sign(payload)).isEqualTo(signer.sign(payload));
  }

  @Test
  void signsTwoSeatsDifferently() {
    assertThat(signer.sign(payload("A-1-1"))).isNotEqualTo(signer.sign(payload("A-1-2")));
  }

  @Test
  void keepsTheInstantItWasIssuedAt() {
    TicketPayload verified = signer.verify(signer.sign(payload("A-1-1")));

    assertThat(verified.issuedAt()).isEqualTo(ISSUED_AT);
  }

  @Test
  void refusesToBeBuiltOrCalledWithoutWhatItNeeds() {
    assertThatThrownBy(() -> new TicketSigner(null, keys.getPublic()))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new TicketSigner(keys.getPrivate(), null))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> signer.sign(null)).isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> signer.verify(null)).isInstanceOf(NullPointerException.class);
  }

  private static TicketPayload payload(String seatId) {
    return new TicketPayload(TICKET_ID, EVENT_ID, seatId, ISSUED_AT);
  }
}
