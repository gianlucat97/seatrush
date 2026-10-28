package com.seatrush.gateway.config;

import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Where the services behind the gateway are (README section 22).
 *
 * <p>Three URLs and no discovery. On Kubernetes each of these is a service name, so the cluster
 * resolves them and nothing here has to know how many pods are behind one; locally they are the
 * ports of README section 23. A registry would be a moving part this system does not need, since
 * the set of services is fixed and known at build time.
 *
 * @param inventory events, the seat map and holds
 * @param queue the lobby, the waiting room and the admission a buyer leaves with
 * @param order checkout and the tickets it issues
 */
@Validated
@ConfigurationProperties(prefix = "seatrush.gateway")
public record DownstreamProperties(
    @NotBlank @DefaultValue("http://localhost:8081") String inventory,
    @NotBlank @DefaultValue("http://localhost:8084") String queue,
    @NotBlank @DefaultValue("http://localhost:8082") String order) {}
