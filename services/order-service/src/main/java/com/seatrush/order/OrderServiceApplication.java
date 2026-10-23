package com.seatrush.order;

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
public class OrderServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(OrderServiceApplication.class, args);
  }
}
