package com.seatrush.security.config;

import com.seatrush.security.tickets.TicketKeys;
import com.seatrush.security.tickets.TicketSigner;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

/**
 * Contributes the ticket signer to whichever service issues tickets (README section 13).
 *
 * <p>Today that is order-service alone, and the module is shared rather than part of it because the
 * other two signatures README section 20 puts here, the user JWT of the gateway and the admission
 * token of the queue, are the same shape of problem. Those two arrive through {@link
 * UserTokenConfiguration} and {@link AdmissionTokenConfiguration}, which a service imports rather
 * than inherits: the warning below is worth reading, and printing it in services that sign nothing
 * would be the surest way to make everybody stop reading it.
 *
 * <p>A service with no key configured gets a generated one and a warning that names the two things
 * it costs: tickets will not survive a restart, and a second pod will mint tickets the first cannot
 * check. Both are fine on a laptop and neither is fine anywhere else, which is exactly what the
 * warning has to make obvious.
 */
@AutoConfiguration
@EnableConfigurationProperties(TicketSigningProperties.class)
public class SecurityAutoConfiguration {

  private static final Logger log = LoggerFactory.getLogger(SecurityAutoConfiguration.class);

  @Bean
  @ConditionalOnMissingBean
  public TicketSigner ticketSigner(TicketSigningProperties properties) {
    if (!properties.isConfigured()) {
      KeyPair generated = TicketKeys.generate();
      log.warn(
          "no ticket signing key is configured, so one was generated for this process: tickets "
              + "will not verify after a restart, and a second pod will mint tickets this one "
              + "cannot check. Set seatrush.tickets.private-key and seatrush.tickets.public-key "
              + "outside a developer machine. The generated public key is {}",
          TicketKeys.encode(generated.getPublic().getEncoded()));
      return new TicketSigner(generated.getPrivate(), generated.getPublic());
    }

    PrivateKey signingKey =
        TicketKeys.privateKey(
            properties
                .privateKeyOrNone()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "seatrush.tickets.public-key is set without a private key, so this "
                                + "service can check tickets but not issue any")));
    PublicKey verifyingKey =
        TicketKeys.publicKey(
            properties
                .publicKeyOrNone()
                .orElseThrow(
                    () ->
                        new IllegalStateException(
                            "seatrush.tickets.private-key is set without a public key, so nothing "
                                + "could check what this service signed")));
    log.info("tickets will be signed with the configured Ed25519 key");
    return new TicketSigner(signingKey, verifyingKey);
  }
}
