package com.seatrush.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.gateway.config.RateLimitProperties.Bucket;
import com.seatrush.gateway.config.RateLimitProperties.Limits;
import com.seatrush.gateway.domain.RateLimitScope;
import com.seatrush.gateway.domain.TokenBuckets.SubjectKind;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

/**
 * The six pairs of numbers of README section 22, and the lookup that picks one of them.
 *
 * <p>Two different things are checked and they need two different contexts. The binding is a
 * question about this record, so it is asked with properties set by hand. The shipped numbers are a
 * claim about the sale of section 18.1, and they live in {@code application.yml} rather than in the
 * record, so they are asked of a context that has actually read that file: a test that asserted the
 * record defaults would pass while the file said something else entirely.
 */
class RateLimitPropertiesTest {

  /** Nothing but this record, for questions about how it binds. */
  private final ApplicationContextRunner bare =
      new ApplicationContextRunner().withUserConfiguration(Bound.class);

  /** The same, having read {@code application.yml}, for questions about what this service ships. */
  private final ApplicationContextRunner shipped =
      new ApplicationContextRunner()
          .withInitializer(new ConfigDataApplicationContextInitializer())
          .withUserConfiguration(Bound.class);

  @Test
  void bindsEveryScopeFromTheKebabCaseKeys() {
    bare.withPropertyValues(
            "seatrush.ratelimit.queue-join.user.burst=7",
            "seatrush.ratelimit.queue-join.user.per-second=1.5",
            "seatrush.ratelimit.hold.ip.burst=900",
            "seatrush.ratelimit.order.user.per-second=0.5")
        .run(
            bound -> {
              RateLimitProperties properties = bound.getBean(RateLimitProperties.class);

              assertThat(properties.queueJoin().user().burst()).isEqualTo(7);
              assertThat(properties.queueJoin().user().perSecond()).isEqualTo(1.5);
              assertThat(properties.hold().ip().burst()).isEqualTo(900);
              assertThat(properties.order().user().perSecond()).isEqualTo(0.5);
            });
  }

  @Test
  void isOnUnlessADeploymentTurnsItOff() {
    shipped.run(bound -> assertThat(bound.getBean(RateLimitProperties.class).enabled()).isTrue());

    bare.withPropertyValues("seatrush.ratelimit.enabled=false")
        .run(bound -> assertThat(bound.getBean(RateLimitProperties.class).enabled()).isFalse());
  }

  @Test
  void bindsNothingAtAllAndStillAnswersWithABucket() {
    bare.run(
        bound -> {
          RateLimitProperties properties = bound.getBean(RateLimitProperties.class);

          assertThat(properties.queueJoin()).isNotNull();
          assertThat(properties.hold()).isNotNull();
          assertThat(properties.order()).isNotNull();
        });
  }

  /**
   * The lookup every limited request goes through, asked for all six combinations.
   *
   * <p>A scope whose numbers nobody configured would be a limit that throws on the first request of
   * the next sale, which is the worst moment to find out.
   */
  @Test
  void hasABucketForEveryScopeAndEverySubject() {
    shipped.run(
        bound -> {
          RateLimitProperties properties = bound.getBean(RateLimitProperties.class);

          for (RateLimitScope scope : RateLimitScope.values()) {
            for (SubjectKind kind : SubjectKind.values()) {
              Bucket bucket = properties.bucketFor(scope, kind);

              assertThat(bucket).as("%s / %s", scope, kind).isNotNull();
              assertThat(bucket.burst()).as("%s / %s burst", scope, kind).isPositive();
              assertThat(bucket.perSecond()).as("%s / %s rate", scope, kind).isPositive();
            }
          }
        });
  }

  /**
   * The claim the shipped numbers make, asserted so that a later edit cannot quietly invert it.
   *
   * <p>A limit per address has to hold a whole office or a mobile carrier behind one address, so it
   * is there to stop a script rather than to shape a buyer. An address bucket tighter than a user
   * bucket would refuse the second buyer of every shared connection.
   */
  @Test
  void letsAnAddressDoMoreThanOneBuyer() {
    shipped.run(
        bound -> {
          RateLimitProperties properties = bound.getBean(RateLimitProperties.class);

          for (RateLimitScope scope : RateLimitScope.values()) {
            Limits limits = properties.limitsFor(scope);

            assertThat(limits.ip().burst())
                .as("%s burst", scope)
                .isGreaterThan(limits.user().burst());
            assertThat(limits.ip().perSecond())
                .as("%s rate", scope)
                .isGreaterThan(limits.user().perSecond());
          }
        });
  }

  /**
   * A hold is the request a flash on-sale legitimately repeats, so it is the loosest of the three.
   *
   * <p>A buyer loses a seat to somebody else and reaches again, which the simulation of README
   * section 18.1.1 does up to three times per attempt. An order is the opposite: one hold becomes
   * one order, and a second attempt is a retry of a {@code 503} or a mistake.
   */
  @Test
  void letsABuyerReachForSeatsMoreOftenThanItLetsThemOrder() {
    shipped.run(
        bound -> {
          RateLimitProperties properties = bound.getBean(RateLimitProperties.class);

          assertThat(properties.hold().user().burst())
              .isGreaterThan(properties.order().user().burst());
          assertThat(properties.hold().user().perSecond())
              .isGreaterThan(properties.order().user().perSecond());
        });
  }

  @Configuration(proxyBeanMethods = false)
  @EnableConfigurationProperties(RateLimitProperties.class)
  static class Bound {}
}
