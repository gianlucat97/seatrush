package com.seatrush.observability.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.prometheusmetrics.PrometheusMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.prometheus.PrometheusMetricsExportAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;

class ObservabilityAutoConfigurationTest {

  /**
   * Boot's own metrics auto-configuration is part of the subject.
   *
   * <p>What this module relies on is that Boot collects {@code MeterFilter} beans and applies them
   * to the registries it builds. Calling the filters by hand would assert that they do what they
   * say and not that anything ever asks them to.
   */
  private final ApplicationContextRunner runner =
      new ApplicationContextRunner()
          .withConfiguration(
              AutoConfigurations.of(
                  MetricsAutoConfiguration.class,
                  CompositeMeterRegistryAutoConfiguration.class,
                  PrometheusMetricsExportAutoConfiguration.class,
                  ObservabilityAutoConfiguration.class))
          .withPropertyValues("spring.application.name=inventory-service");

  @Test
  void bringsAPrometheusRegistryWithTheModuleAlone() {
    runner.run(
        context -> {
          assertThat(context).hasSingleBean(PrometheusMeterRegistry.class);
          assertThat(context.getBean(PrometheusMeterRegistry.class).scrape())
              .describedAs("the registry has to be able to produce a scrape body")
              .isNotNull();
        });
  }

  @Test
  void tagsEveryMeterWithTheServiceItCameFrom() {
    runner.run(
        context -> {
          MeterRegistry meters = context.getBean(MeterRegistry.class);
          meters.counter("seatrush.holds", "result", "ok").increment();

          assertThat(meters.get("seatrush.holds").counter().getId().getTag("service"))
              .isEqualTo("inventory-service");
        });
  }

  @Test
  void leavesTheTagOutWhenTheServiceIsUnnamed() {
    new ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                MetricsAutoConfiguration.class,
                CompositeMeterRegistryAutoConfiguration.class,
                ObservabilityAutoConfiguration.class))
        .run(
            context -> {
              // No tag filter at all rather than one reading "unknown", which would be worse
              // than nothing: a dashboard would select on it and find something.
              assertThat(context).doesNotHaveBean("seatrushServiceTag");
              assertThat(context).hasBean("seatrushLatencyHistograms");
              MeterRegistry meters = context.getBean(MeterRegistry.class);
              meters.counter("seatrush.holds").increment();
              assertThat(meters.get("seatrush.holds").counter().getId().getTag("service")).isNull();
            });
  }

  /**
   * The predicate of each stack, and only its own.
   *
   * <p>Both are asserted by absence as much as by presence: the servlet predicate loaded into the
   * gateway would fail on its first request, where there is no servlet API to resolve.
   */
  @Test
  void registersTheServletActuatorPredicateInAServletApplication() {
    new WebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                MetricsAutoConfiguration.class,
                CompositeMeterRegistryAutoConfiguration.class,
                ObservabilityAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasBean("seatrushServletActuatorRequestsAreNotObserved");
              assertThat(context).doesNotHaveBean("seatrushReactiveActuatorRequestsAreNotObserved");
            });
  }

  @Test
  void registersTheReactiveActuatorPredicateInAReactiveApplication() {
    new ReactiveWebApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                MetricsAutoConfiguration.class,
                CompositeMeterRegistryAutoConfiguration.class,
                ObservabilityAutoConfiguration.class))
        .run(
            context -> {
              assertThat(context).hasBean("seatrushReactiveActuatorRequestsAreNotObserved");
              assertThat(context).doesNotHaveBean("seatrushServletActuatorRequestsAreNotObserved");
            });
  }

  @Test
  void givesTheDomainTimersTheirBuckets() {
    runner.run(
        context -> {
          MeterRegistry meters = context.getBean(MeterRegistry.class);
          Timer hold = meters.timer("seatrush.hold.duration");
          hold.record(Duration.ofMillis(3));

          assertThat(hold.takeSnapshot().histogramCounts()).isNotEmpty();
        });
  }

  @Test
  void exportsTheDomainTimerAsBucketsPrometheusCanRead() {
    runner.run(
        context -> {
          MeterRegistry meters = context.getBean(MeterRegistry.class);
          meters.timer("seatrush.hold.duration").record(Duration.ofMillis(3));

          String scrape = context.getBean(PrometheusMeterRegistry.class).scrape();

          // The exported names the dashboards and the alert rules of deploy/prometheus use, which
          // is the half of this module that a unit test on the filter cannot see.
          assertThat(scrape)
              .contains("seatrush_hold_duration_seconds_bucket")
              .contains("service=\"inventory-service\"")
              .contains("le=\"0.05\"");
        });
  }
}
