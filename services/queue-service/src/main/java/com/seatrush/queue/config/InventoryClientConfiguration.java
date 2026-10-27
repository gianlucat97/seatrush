package com.seatrush.queue.config;

import java.net.http.HttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * The client the catalog read goes out on.
 *
 * <p>Both timeouts are set explicitly, because the default of an HTTP client is to wait for ever
 * and a refresh that never returns is a scheduled task that never runs again. The read timeout is
 * below the refresh interval for the same reason.
 *
 * <p>The JDK client rather than a pooled one, because this service runs on virtual threads: a
 * blocking call parks a continuation rather than a platform thread, so the connection pool is the
 * only thing that would need sizing and the JDK client sizes it itself.
 */
@Configuration(proxyBeanMethods = false)
public class InventoryClientConfiguration {

  @Bean
  public RestClient inventoryRestClient(InventoryProperties properties) {
    HttpClient httpClient =
        HttpClient.newBuilder().connectTimeout(properties.connectTimeout()).build();
    JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
    requestFactory.setReadTimeout(properties.readTimeout());

    return RestClient.builder()
        .baseUrl(properties.baseUrl())
        .requestFactory(requestFactory)
        .build();
  }
}
