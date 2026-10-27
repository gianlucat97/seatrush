package com.seatrush.security.tokens;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** What counts as a secret, and what is quietly not one. */
class TokenSecretsTest {

  @Test
  void generatesSomethingLongEnoughToSignWith() {
    String secret = TokenSecrets.generate();

    assertThat(Base64.getDecoder().decode(secret)).hasSize(TokenSecrets.MINIMUM_BYTES);
    assertThat(TokenSecrets.key(secret).getAlgorithm()).isEqualTo("HmacSHA256");
  }

  @Test
  void generatesADifferentOneEveryTime() {
    Set<String> generated = new HashSet<>();
    for (int i = 0; i < 100; i++) {
      generated.add(TokenSecrets.generate());
    }

    assertThat(generated).hasSize(100);
  }

  /**
   * The check that matters. A short secret still signs and still verifies, so nothing complains and
   * the tokens look exactly right; what has changed is only how long a brute force takes, which is
   * the one property nobody can observe from the outside.
   */
  @Test
  void refusesASecretShorterThanTheAlgorithmNeeds() {
    byte[] tooShort = new byte[TokenSecrets.MINIMUM_BYTES - 1];

    assertThatThrownBy(() -> TokenSecrets.key(Base64.getEncoder().encodeToString(tooShort)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32 bytes")
        .hasMessageContaining("is 31");
  }

  /** A password somebody typed is the usual way a short secret arrives. */
  @Test
  void refusesAPassphraseThatHappensToBeBase64() {
    assertThatThrownBy(() -> TokenSecrets.key("c2VhdHJ1c2g="))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("at least 32 bytes");
  }

  @Test
  void refusesSomethingThatIsNotBase64() {
    assertThatThrownBy(() -> TokenSecrets.key("not base64 at all !!"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be base64");
  }

  @Test
  void refusesNothingAtAll() {
    assertThatThrownBy(() -> TokenSecrets.key("  "))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
    assertThatThrownBy(() -> TokenSecrets.key(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must not be blank");
  }

  @Test
  void ignoresTheWhitespaceAnEnvironmentFileAdds() {
    String secret = TokenSecrets.generate();

    assertThat(TokenSecrets.key("\n" + secret + "  ").getEncoded())
        .isEqualTo(TokenSecrets.key(secret).getEncoded());
  }
}
