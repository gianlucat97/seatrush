package com.seatrush.realtime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.testsupport.containers.KafkaSupport;
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
 * The OpenAPI document of this service, published as {@code docs/api/realtime-service.yaml}.
 *
 * <p>One endpoint, and the one in the system whose response body OpenAPI describes least well: what
 * {@code text/event-stream} carries is a sequence of named events over an open connection, and the
 * published document can say the media type and not the grammar. The event names and their payloads
 * are in README section 12.2 and in the message schemas, which is where a client reads them.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfDockerAvailable
class OpenApiIT {

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void broker(DynamicPropertyRegistry registry) {
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

    OpenApiDocs.publish("realtime-service", document);
  }
}
