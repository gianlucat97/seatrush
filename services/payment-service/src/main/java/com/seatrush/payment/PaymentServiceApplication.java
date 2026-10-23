package com.seatrush.payment;

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
public class PaymentServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(PaymentServiceApplication.class, args);
  }
}
