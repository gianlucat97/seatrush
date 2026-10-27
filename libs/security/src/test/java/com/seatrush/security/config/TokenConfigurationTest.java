package com.seatrush.security.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.security.tokens.AdmissionTokenSigner;
import com.seatrush.security.tokens.TokenException;
import com.seatrush.security.tokens.TokenSecrets;
import com.seatrush.security.tokens.UserToken;
import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.testsupport.time.MutableClock;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** What a service gets by importing one of the token configurations, and what it does not get. */
class TokenConfigurationTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  private final MutableClock clock = MutableClock.atArbitraryInstant();

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner().withBean(Clock.class, () -> clock);

  /**
   * The point of importing rather than auto-configuring: queue-service signs admissions and has no
   * business minting identities, so asking for one configuration must not quietly bring the other.
   */
  @Test
  void bringsOnlyTheSignerTheServiceAskedFor() {
    runner
        .withUserConfiguration(AdmissionTokenConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(AdmissionTokenSigner.class);
              assertThat(context).doesNotHaveBean(UserTokenSigner.class);
            });

    runner
        .withUserConfiguration(UserTokenConfiguration.class)
        .run(
            context -> {
              assertThat(context).hasSingleBean(UserTokenSigner.class);
              assertThat(context).doesNotHaveBean(AdmissionTokenSigner.class);
            });
  }

  @Test
  void signsUserTokensWithTheConfiguredSecret() {
    String secret = TokenSecrets.generate();

    runner
        .withUserConfiguration(UserTokenConfiguration.class)
        .withPropertyValues("seatrush.auth.secret=" + secret, "seatrush.auth.token-ttl=30m")
        .run(
            context -> {
              UserTokenSigner signer = context.getBean(UserTokenSigner.class);
              assertThat(signer.ttl()).isEqualTo(Duration.ofMinutes(30));

              String token = signer.issue(USER_ID, "ada", Set.of(UserToken.ADMIN)).compact();
              UserTokenSigner gateway = new UserTokenSigner(key(secret), clock, Duration.ofDays(1));
              assertThat(gateway.verify(token).admin())
                  .as("so another pod holding the same secret reads the same identity")
                  .isTrue();
            });
  }

  @Test
  void signsAdmissionsWithTheConfiguredSecret() {
    String secret = TokenSecrets.generate();

    runner
        .withUserConfiguration(AdmissionTokenConfiguration.class)
        .withPropertyValues("seatrush.admission.secret=" + secret)
        .run(
            context -> {
              AdmissionTokenSigner queue = context.getBean(AdmissionTokenSigner.class);
              assertThat(queue.ttl())
                  .as("the default of README section 22")
                  .isEqualTo(Duration.ofMinutes(10));

              String token = queue.issue(USER_ID, EVENT_ID).compact();
              AdmissionTokenSigner gateway =
                  new AdmissionTokenSigner(key(secret), clock, Duration.ofMinutes(10));
              assertThat(gateway.verify(token).admits(EVENT_ID)).isTrue();
            });
  }

  /**
   * A laptop with nothing configured still gets working signers, for the reason the ticket key
   * does: a service that refused to start would make the local stack unusable for the sake of a
   * check Kubernetes already does. The warning is what makes that visible.
   */
  @Test
  void generatesASecretWhenNoneIsConfigured() {
    runner
        .withUserConfiguration(UserTokenConfiguration.class, AdmissionTokenConfiguration.class)
        .run(
            context -> {
              UserTokenSigner users = context.getBean(UserTokenSigner.class);
              AdmissionTokenSigner admissions = context.getBean(AdmissionTokenSigner.class);

              assertThat(users.verify(users.issue(USER_ID, "ada", Set.of()).compact()).username())
                  .isEqualTo("ada");
              assertThat(admissions.verify(admissions.issue(USER_ID, EVENT_ID).compact()).eventId())
                  .isEqualTo(EVENT_ID);
            });
  }

  /**
   * Two generated secrets and not one, so neither signer can be used to forge the other's token.
   */
  @Test
  void keepsTheTwoGeneratedSecretsApart() {
    runner
        .withUserConfiguration(UserTokenConfiguration.class, AdmissionTokenConfiguration.class)
        .run(
            context -> {
              String login =
                  context.getBean(UserTokenSigner.class).issue(USER_ID, "ada", Set.of()).compact();
              AdmissionTokenSigner admissions = context.getBean(AdmissionTokenSigner.class);

              assertThatThrownBy(() -> admissions.verify(login))
                  .isInstanceOf(TokenException.class)
                  .hasMessageContaining("did not sign");
            });
  }

  @Test
  void refusesToStartWithASecretTooShortToBeOne() {
    runner
        .withUserConfiguration(AdmissionTokenConfiguration.class)
        .withPropertyValues("seatrush.admission.secret=c2VhdHJ1c2g=")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("at least 32 bytes"));
  }

  @Test
  void refusesToStartWithALifetimeThatIsNotOne() {
    runner
        .withUserConfiguration(UserTokenConfiguration.class)
        .withPropertyValues("seatrush.auth.token-ttl=0s")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("seatrush.auth.token-ttl must be positive"));

    runner
        .withUserConfiguration(AdmissionTokenConfiguration.class)
        .withPropertyValues("seatrush.admission.token-ttl=-1m")
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("seatrush.admission.token-ttl must be positive"));
  }

  @Test
  void stepsAsideWhenTheServiceDeclaresItsOwnSigner() {
    UserTokenSigner own =
        new UserTokenSigner(key(TokenSecrets.generate()), clock, Duration.ofMinutes(1));

    runner
        .withUserConfiguration(UserTokenConfiguration.class)
        .withBean(UserTokenSigner.class, () -> own)
        .run(
            context -> {
              assertThat(context).hasSingleBean(UserTokenSigner.class);
              assertThat(context.getBean(UserTokenSigner.class)).isSameAs(own);
            });
  }

  private static SecretKeySpec key(String base64Secret) {
    return TokenSecrets.key(base64Secret);
  }
}
