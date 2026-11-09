package com.seatrush.inventory;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.seatrush.testsupport.containers.PostgresSupport;
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
 * The OpenAPI document of this service, published as {@code docs/api/inventory-service.yaml}.
 *
 * <p>It is asked of a real application context rather than of a web slice, and that is the whole
 * design of it. The document is built from the handler mappings, the argument resolvers and the
 * Jackson configuration that are actually in effect, so a slice with its own mapper and a subset of
 * the beans could publish a contract no deployed pod serves. That is the same failure the message
 * schemas of README section 17.3 avoid by generating from the records.
 *
 * <p>Which is also why this is an integration test and not a unit test: a context that describes
 * this service needs the stores this service holds.
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfDockerAvailable
class OpenApiIT {

  private static final String DATABASE = "inventory_openapi_it";

  @Autowired private MockMvc mvc;

  @DynamicPropertySource
  static void stores(DynamicPropertyRegistry registry) {
    PostgresSupport.dropDatabase(DATABASE);
    PostgresSupport.createDatabase(DATABASE);
    PostgresSupport.registerProperties(registry, DATABASE);
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

    OpenApiDocs.publish("inventory-service", document);
  }
}
