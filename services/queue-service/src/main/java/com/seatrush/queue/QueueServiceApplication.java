package com.seatrush.queue;

import com.seatrush.security.config.AdmissionTokenConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The queue service: the lobby, the FIFO waiting room and the admission of buyers (README section
 * 6.2).
 *
 * <p>What is here today is the lobby, the queue, the shuffle that drains one into the other when a
 * sale opens, the door that lets a bounded number of buyers through it with a signed admission, and
 * the three endpoints a client uses to join, read its place and leave. The SSE stream that pushes a
 * position instead of answering one, and the sold-out notice that closes a queue, arrive with the
 * feature that follows.
 *
 * <p>{@link AdmissionTokenConfiguration} is imported rather than auto-configured, which is how
 * {@code libs/security} contributes a signer only to the services that need one: this service and
 * the gateway hold the admission secret, and nothing else does.
 *
 * <p>It owns no database. Everything it knows is in Redis or read from inventory's catalog, which
 * is why README section 11 lists three databases for six services.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// The catalog refresh, the lobby opener and the admission job all run on a schedule, on every
// pod at once: what makes that safe is the Lua scripts they call rather than a leader.
@EnableScheduling
@Import(AdmissionTokenConfiguration.class)
public class QueueServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QueueServiceApplication.class, args);
  }
}
