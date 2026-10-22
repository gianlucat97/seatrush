package com.seatrush.inventory;

import com.seatrush.inventory.domain.LayoutGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;

/**
 * The inventory service: events, venue layout, seat state, holds and the record of sales (README
 * section 6.3).
 *
 * <p>This milestone brings the catalog and the layout generator. Seat state, holds and the Redis
 * side of the service arrive with the features that follow, which is why there is no Redis
 * dependency here yet.
 */
@SpringBootApplication
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
