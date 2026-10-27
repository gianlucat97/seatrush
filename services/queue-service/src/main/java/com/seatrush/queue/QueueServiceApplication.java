package com.seatrush.queue;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The queue service: the lobby, the FIFO waiting room and the admission of buyers (README section
 * 6.2).
 *
 * <p>What is here today is the lobby, the queue, the shuffle that drains one into the other when a
 * sale opens, and the three endpoints a client uses to join, read its place and leave. The
 * admission job, the token it hands out and the SSE stream that pushes a position arrive with the
 * features that follow; the position is already computed against the admission head, so nothing
 * about it changes when something starts moving that head.
 *
 * <p>It owns no database. Everything it knows is in Redis or read from inventory's catalog, which
 * is why README section 11 lists three databases for six services.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// The catalog refresh and the lobby opener run on a schedule, on every pod at once.
@EnableScheduling
public class QueueServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QueueServiceApplication.class, args);
  }
}
