package com.seatrush.payment;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The payment service: a simulated provider with an audit trail (README section 6.5).
 *
 * <p>It is the simplest service of the system on purpose. What it exists to do is make the hard
 * parts of the others testable: a configurable decline rate and a configurable latency tail are
 * what turn the compensations of the checkout SAGA from a code path nobody exercises into one a
 * benchmark drives thousands of times.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// The Postgres outbox relay of seatrush-messaging runs on a schedule, on every pod at once.
@EnableScheduling
// The document served at /v3/api-docs and published as docs/api/payment-service.yaml. The
// server is relative on purpose: left unset springdoc fills it in from the request that asked,
// which is a different address in Compose, in the cluster and in the test that publishes this
// file, and a published contract should not name whichever host generated it.
@OpenAPIDefinition(
    info =
        @Info(
            title = "SeatRush payment-service",
            version = "0.1.0",
            description =
                "A simulated payment provider with an audit trail, whose configurable"
                    + " decline rate and latency tail are what turn the compensations of"
                    + " the checkout SAGA into a path a benchmark drives thousands of"
                    + " times (README section 6.5)."),
    servers =
        @Server(
            url = "/",
            description =
                "The service itself. Every path here is under /internal and none of them is"
                    + " exposed by the gateway (README section 12.7)"))
public class PaymentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
