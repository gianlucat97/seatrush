package com.seatrush.order;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The order service: the checkout SAGA and the orders it produces (README section 6.4).
 *
 * <p>What is here today is the entry point of a checkout: the synchronous freeze of a hold, the
 * order it creates and the {@code AuthorizePayment} it puts in the outbox. The state machine that
 * advances the SAGA on replies, the timeout scheduler, the capture retries and the tickets arrive
 * with the features that follow.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// The Postgres outbox relay of seatrush-messaging runs on a schedule, on every pod at once.
@EnableScheduling
// The document served at /v3/api-docs and published as docs/api/order-service.yaml. The
// server is relative on purpose: left unset springdoc fills it in from the request that asked,
// which is a different address in Compose, in the cluster and in the test that publishes this
// file, and a published contract should not name whichever host generated it.
@OpenAPIDefinition(
    info =
        @Info(
            title = "SeatRush order-service",
            version = "0.1.0",
            description = "The checkout SAGA and the orders it produces (README section 6.4)."),
    servers =
        @Server(
            url = "/",
            description = "The service itself. The /api paths are reached through the gateway"))
public class OrderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
  }
}
