package com.seatrush.queue;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.testsupport.containers.RedisSupport;
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
 * The OpenAPI document of this service, published as {@code docs/api/queue-service.yaml}.
 *
 * <p>Asked of a real application context rather than of a web slice, for the reason the whole
 * directory exists: the document is built from the handler mappings, the argument resolvers and the
 * Jackson configuration that are actually in effect, so a slice with its own mapper and a subset of
 * the beans could publish a contract no deployed pod serves.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfDockerAvailable
class OpenApiIT {

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    RedisSupport.registerProperties(registry);
  }

  @Test
  void publishesTheDocumentThisServiceServes() throws Exception {
    String document =
        mvc.perform(get("/v3/api-docs.yaml"))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);

    OpenApiDocs.publish("queue-service", document);
  }
}
