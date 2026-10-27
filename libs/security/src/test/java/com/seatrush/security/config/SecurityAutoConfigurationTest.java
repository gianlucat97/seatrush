package com.seatrush.security.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.security.tickets.TicketKeys;
import com.seatrush.security.tickets.TicketPayload;
import com.seatrush.security.tickets.TicketSigner;
import java.security.KeyPair;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

/** What a service gets by depending on this module, with a key and without one. */
class SecurityAutoConfigurationTest {

  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(AutoConfigurations.of(SecurityAutoConfiguration.class));

  @Test
  void signsWithTheConfiguredKey() {
    KeyPair keys = TicketKeys.generate();

    runner
        .withPropertyValues(
            "seatrush.tickets.private-key=" + TicketKeys.encode(keys.getPrivate().getEncoded()),
            "seatrush.tickets.public-key=" + TicketKeys.encode(keys.getPublic().getEncoded()))
        .run(
            context -> {
              assertThat(context).hasSingleBean(TicketSigner.class);
              String signed = context.getBean(TicketSigner.class).sign(aPayload());

              TicketSigner gate = new TicketSigner(keys.getPrivate(), keys.getPublic());
              assertThat(gate.verify(signed))
                  .as("so a scanner holding that key admits what this service issued")
                  .isEqualTo(aPayload());
            });
  }

  /**
   * A laptop with nothing configured still gets a working signer, because a service that refused to
   * start would make the local stack unusable for a check Kubernetes already does.
   */
  @Test
  void generatesAKeyWhenNoneIsConfigured() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(TicketSigner.class);
          TicketSigner signer = context.getBean(TicketSigner.class);

          assertThat(signer.verify(signer.sign(aPayload()))).isEqualTo(aPayload());
        });
  }

  /** Half a key is the one configuration that is worse than none, so it is refused. */
  @Test
  void refusesToStartWithOnlyHalfAKey() {
    KeyPair keys = TicketKeys.generate();

    runner
        .withPropertyValues(
            "seatrush.tickets.private-key=" + TicketKeys.encode(keys.getPrivate().getEncoded()))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .hasRootCauseMessage(
                        "seatrush.tickets.private-key is set without a public key, so nothing "
                            + "could check what this service signed"));

    runner
        .withPropertyValues(
            "seatrush.tickets.public-key=" + TicketKeys.encode(keys.getPublic().getEncoded()))
        .run(
            context ->
                assertThat(context)
                    .hasFailed()
                    .getFailure()
                    .rootCause()
                    .hasMessageContaining("can check tickets but not issue any"));
  }

  @Test
  void stepsAsideWhenTheServiceDeclaresItsOwnSigner() {
    KeyPair keys = TicketKeys.generate();
    TicketSigner own = new TicketSigner(keys.getPrivate(), keys.getPublic());

    runner
        .withBean(TicketSigner.class, () -> own)
        .run(
            context -> {
              assertThat(context).hasSingleBean(TicketSigner.class);
              assertThat(context.getBean(TicketSigner.class)).isSameAs(own);
            });
  }

  private static TicketPayload aPayload() {
    return new TicketPayload(
        UUID.fromString("0199c5f0-7777-7000-8000-00000000f007"),
        UUID.fromString("0199c5f0-2222-7000-8000-00000000b002"),
        "A-1-1",
        Instant.parse("2026-06-01T10:00:00Z"));
  }
}
