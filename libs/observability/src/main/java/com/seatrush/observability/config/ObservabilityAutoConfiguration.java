package com.seatrush.observability.config;

import com.seatrush.observability.metrics.ActuatorRequests;
import com.seatrush.observability.metrics.LatencyHistograms;
import com.seatrush.observability.metrics.ReactiveActuatorRequests;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * What every service's metrics have in common (README section 14.1).
 *
 * <p>Three beans and a dependency. The dependency is the Prometheus registry, which is what
 * publishes {@code /actuator/prometheus} and therefore the only reason any of the meters registered
 * since M1 can be read from outside the process they live in. The beans are the decisions that have
 * to be the same in all six services: which timers carry histogram buckets, that every meter says
 * which service it came from, and that nothing measures the monitoring.
 *
 * <p>The first two are {@code MeterFilter} beans rather than registry customizers, because Boot
 * applies filter beans to every registry it builds before any meter is registered on it. A
 * customizer would run too late for a meter created during startup. The third has to be an {@code
 * ObservationPredicate}, because what it leaves out has to be left out of the span as well as of
 * the series.
 */
@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
public class ObservabilityAutoConfiguration {

  /**
   * Tags every meter with the service it came from.
   *
   * <p>Prometheus already labels a scraped series with the job and the instance it came from, and
   * that is exactly why this exists: those two describe how the metric was collected and not what
   * produced it. A dashboard that selects on them is a dashboard written for one deployment, and
   * the same metric arrives under a Compose service name here and a Kubernetes pod there.
   *
   * <p>Boot adds no such tag of its own. The only thing it does with {@code
   * spring.application.name} in the metrics machinery is name a push gateway job, which this stack
   * does not use.
   */
  @Bean
  @ConditionalOnProperty("spring.application.name")
  public MeterFilter seatrushServiceTag(
      @Value("${spring.application.name}") String applicationName) {
    return MeterFilter.commonTags(Tags.of("service", applicationName));
  }

  /** The histogram buckets the p99 panels of README section 14.4 are computed from. */
  @Bean
  public MeterFilter seatrushLatencyHistograms() {
    return LatencyHistograms.filter();
  }

  /**
   * Keeps the scrape and the container probe out of the metrics and the traces.
   *
   * <p>Two configurations, one per web stack, each conditional on the kind of application it is in.
   * Both observation context classes live in {@code spring-web} and are therefore always present,
   * so the condition cannot be on them: what differs is the servlet API behind the servlet one,
   * which the gateway does not carry at all. The condition is what keeps that class from being
   * loaded there, and a bean method naming it unconditionally would fail with a {@code
   * NoClassDefFoundError} on the gateway's first request rather than at its startup.
   */
  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = Type.SERVLET)
  public static class ServletActuatorRequestsConfiguration {

    @Bean
    public ObservationPredicate seatrushServletActuatorRequestsAreNotObserved() {
      return ActuatorRequests.onServlet();
    }
  }

  @Configuration(proxyBeanMethods = false)
  @ConditionalOnWebApplication(type = Type.REACTIVE)
  public static class ReactiveActuatorRequestsConfiguration {

    @Bean
    public ObservationPredicate seatrushReactiveActuatorRequestsAreNotObserved() {
      return ReactiveActuatorRequests.onReactive();
    }
  }
}
