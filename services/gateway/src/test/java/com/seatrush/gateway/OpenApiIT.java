package com.seatrush.gateway;

import com.seatrush.security.tokens.UserTokenSigner;
import com.seatrush.testsupport.docs.OpenApiDocs;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.reactive.server.WebTestClient;

/**
 * The OpenAPI document of the gateway, published as {@code docs/api/gateway.yaml}.
 *
 * <p>It is the shortest of the six and that is the honest answer rather than a gap. The routes this
 * service proxies are Spring Cloud Gateway routes, which are a configuration of predicates and
 * filters and not handler methods, so nothing in the process can be asked what they look like: each
 * is described by the service that answers it, which is what the other five documents are. What is
 * here is the one endpoint the gateway answers itself.
 *
 * <p>The request carries a token, because {@code /v3/api-docs} is not one of the two prefixes
 * {@code UserAuthenticationFilter} lets through unauthenticated. Opening it up would have been the
 * shorter test and the wrong change: the gateway is the only process in this system exposed to the
 * internet, and widening what it serves without a token is not a decision a documentation feature
 * gets to make. In the cluster each service answers its own {@code /v3/api-docs} on its own port,
 * which the gateway does not route at all.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
@ActiveProfiles("local")
class OpenApiIT {

  private static final UUID READER = UUID.fromString("0199c5f0-5555-7000-8000-00000000e007");

  @Autowired private WebTestClient client;
  @Autowired private UserTokenSigner users;

  @Test
  void publishesTheDocumentThisServiceServes() {
    String document =
        client
            .get()
            .uri("/v3/api-docs.yaml")
            .header(
                HttpHeaders.AUTHORIZATION,
                "Bearer " + users.issue(READER, "Ada", Set.of()).compact())
            .exchange()
            .expectStatus()
            .isOk()
            .expectBody(String.class)
            .returnResult()
            .getResponseBody();

    OpenApiDocs.publish("gateway", document);
  }
}
