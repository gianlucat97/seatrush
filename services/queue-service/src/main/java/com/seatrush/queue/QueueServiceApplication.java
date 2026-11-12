package com.seatrush.queue;

import com.seatrush.security.config.AdmissionTokenConfiguration;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.servers.Server;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The queue service: the lobby, the FIFO waiting room and the admission of buyers (README section
 * 6.2).
 *
 * <p>What is here is the lobby, the queue, the shuffle that drains one into the other when a sale
 * opens, the door that lets a bounded number of buyers through it with a signed admission, the four
 * endpoints of README section 12.3, and the stream that pushes a position instead of waiting to be
 * asked for one, tells a buyer the moment they are let in and closes a waiting room when the sale
 * it was for has nothing left to sell.
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
// The document served at /v3/api-docs and published as docs/api/queue-service.yaml. The
// server is relative on purpose: left unset springdoc fills it in from the request that asked,
// which is a different address in Compose, in the cluster and in the test that publishes this
// file, and a published contract should not name whichever host generated it.
@OpenAPIDefinition(
    info =
        @Info(
            title = "SeatRush queue-service",
            version = "0.1.0",
            description =
                "The lobby, the FIFO waiting room, and the door that admits a bounded"
                    + " number of buyers to a sale with a signed admission (README section"
                    + " 6.2)."),
    servers =
        @Server(
            url = "/",
            description = "The service itself. The /api paths are reached through the gateway"))
public class QueueServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(QueueServiceApplication.class, args);
  }
}
