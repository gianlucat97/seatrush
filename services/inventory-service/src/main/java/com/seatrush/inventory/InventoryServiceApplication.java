package com.seatrush.inventory;

import com.seatrush.inventory.domain.LayoutGenerator;
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
