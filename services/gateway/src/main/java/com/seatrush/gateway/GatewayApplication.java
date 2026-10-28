package com.seatrush.gateway;

import com.seatrush.security.config.AdmissionTokenConfiguration;
import com.seatrush.security.config.UserTokenConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;

/**
 * The single entry point of README section 6.1.
 *
 * <p>Everything a client ever calls arrives here, and three things happen before anything is
 * forwarded: the user token is verified, the identity header the services trust is written from it
 * and from nothing else, and the three purchase commands are made to show an admission. What is
 * then routed is a request whose caller is already known, so no service behind this one has to
 * carry a copy of that check.
 *
 * <p>Reactive, unlike every other service in this repository, and for one reason: this process
 * holds the SSE connection of every client waiting for an on-sale, and a thread per connection is
 * exactly the limit README section 15 refuses to accept.
 *
 * <p>It imports both signers, which no other service does. queue-service holds the admission secret
 * because it mints those tokens, order-service holds the ticket key because it signs tickets, and
 * the user secret lives here alone because this is where a login is turned into a token and where
 * one is read back.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@Import({UserTokenConfiguration.class, AdmissionTokenConfiguration.class})
public class GatewayApplication {

  public static void main(String[] args) {
    SpringApplication.run(GatewayApplication.class, args);
  }
}
