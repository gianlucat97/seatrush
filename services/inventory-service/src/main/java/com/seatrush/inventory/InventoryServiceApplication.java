package com.seatrush.inventory;

import com.seatrush.inventory.domain.LayoutGenerator;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The inventory service: events, venue layout, seat state, holds and the record of sales (README
 * section 6.3).
 *
 * <p>What is here today is the catalog, the layout generator, the seat state of a live sale, the
 * hold path up to the freeze that hands a hold to a checkout, and the relay that forwards seat
 * changes to Kafka. The seat commit and the reconciler arrive with the features that follow.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// The hold sweeper of README section 9.3 runs on a schedule, on every pod at once.
@EnableScheduling
// The document served at /v3/api-docs and published as docs/api/inventory-service.yaml. The
// server is relative on purpose: left unset springdoc fills it in from the request that asked,
// which is a different address in Compose, in the cluster and in the test that publishes this
// file, and a published contract should not name whichever host generated it.
@OpenAPIDefinition(
    info =
        @Info(
            title = "SeatRush inventory-service",
            version = "0.1.0",
            description =
                "Events, venue layout, the seat state of a live sale, holds and the record of"
                    + " sales (README section 6.3)."),
    servers =
        @Server(
            url = "/",
            description =
                "The service itself. The /api paths are reached through the gateway; /internal is"
                    + " not exposed by it (README section 12.7)"))
public class InventoryServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(InventoryServiceApplication.class, args);
  }

  /**
   * The layout generator, which is a domain service and therefore knows nothing about Spring.
   *
   * <p>It is declared here rather than annotated, so the domain stays free of framework code as
   * README section 20 requires and the ArchUnit rules enforce.
   */
  @Bean
  public LayoutGenerator layoutGenerator() {
    return new LayoutGenerator();
  }
}
