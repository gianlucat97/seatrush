package com.seatrush.payment;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.testsupport.containers.KafkaSupport;
import com.seatrush.testsupport.containers.PostgresSupport;
import com.seatrush.testsupport.docs.OpenApiDocs;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

/**
 * The OpenAPI document of this service, published as {@code docs/api/payment-service.yaml}.
 *
 * <p>Every path in it is under {@code /internal} and none of them is exposed by the gateway, which
 * is the point of publishing it: the operator endpoint that sets the decline rate and the latency
 * tail of a benchmark run is the one part of this service a person calls by hand.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfDockerAvailable
class OpenApiIT {

  private static final String DATABASE = "payment_openapi_it";

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
    KafkaSupport.registerProperties(registry);
  }

  @Test
  void publishesTheDocumentThisServiceServes() throws Exception {
    String document =
        mvc.perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    OpenApiDocs.publish("payment-service", document);
  }
}
