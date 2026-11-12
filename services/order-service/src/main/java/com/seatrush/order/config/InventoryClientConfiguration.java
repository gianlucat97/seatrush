package com.seatrush.order.config;

import io.micrometer.observation.ObservationRegistry;
import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client the freeze call goes out on (README section 12.7).
 *
 * <p>Both timeouts are set explicitly, because the default of an HTTP client is to wait forever and
 * a checkout that waits forever holds a request thread of this service and a frozen hold in
 * inventory. The read timeout is deliberately shorter than the SAGA deadline: a freeze that hangs
 * should give the user an error they can retry while their hold is still alive.
 *
 * <p>The JDK client rather than a pooled one, because this service runs on virtual threads: a
 * blocking call that parks a virtual thread costs a continuation and not a platform thread, so the
 * connection pool is the only thing that needs sizing and the JDK client sizes it itself.
 *
 * <p>The observation registry is handed over explicitly, and that one line is what puts this call
 * in the trace. A client assembled here rather than taken from a configured builder gets no
 * instrumentation of its own, and this is the only synchronous hop a checkout has (README section
 * 8.3): everything after it travels as a message and carries its context in the envelope, so a bare
 * client here would have left the one gap in the trace that nothing else could close. With the
 * registry, the request opens a client span and sends the {@code traceparent} header that makes
 * inventory's freeze a child of this checkout.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryClientConfiguration {

  @Bean
  public RestClient inventoryRestClient(
      InventoryProperties properties, ObservationRegistry observations) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());

    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .observationRegistry(observations)
        .build();
  }
}
