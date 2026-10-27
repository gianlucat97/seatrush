package com.seatrush.queue.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rules of README sections 17 and 20, enforced instead of described.
 *
 * <p>The one that matters most is the first: the domain is where the invariants live, and it stays
 * readable and testable without a container only as long as no framework reaches into it.
 */
@AnalyzeClasses(
    packages = "com.seatrush.queue",
    importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

  @ArchTest
  static final ArchRule theDomainKnowsNoFramework =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework..",
              "jakarta..",
              "tools.jackson..",
              "com.fasterxml.jackson..",
              "javax..");

  @ArchTest
  static final ArchRule theDomainKnowsNoAdapter =
      noClasses()
          .that()
          .resideInAPackage("..domain..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage("..adapters..", "..application..");

  /**
   * The use cases orchestrate and own the schedule, so they see Spring's scheduling and stereotype
   * annotations; nothing of the web or of an HTTP client belongs to them.
   */
  @ArchTest
  static final ArchRule theUseCasesKnowNoTransport =
      noClasses()
          .that()
          .resideInAPackage("..application..")
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "org.springframework.web..",
              "org.springframework.http..",
              "org.springframework.data.redis..",
              "jakarta.servlet..",
              "jakarta.validation..",
              "..adapters..");

  /** No service depends on another one: they meet over Kafka and HTTP, never over a classpath. */
  @ArchTest
  static final ArchRule noOtherServiceIsOnTheClasspath =
      noClasses()
          .should()
          .dependOnClassesThat()
          .resideInAnyPackage(
              "com.seatrush.inventory..",
              "com.seatrush.order..",
              "com.seatrush.payment..",
              "com.seatrush.gateway..",
              "com.seatrush.realtime..");

  @ArchTest
  static final ArchRule theLayersPointInwards =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Bootstrap")
          .definedBy("com.seatrush.queue")
          .layer("Web")
          .definedBy("..adapters.in..")
          .layer("Outbound")
          .definedBy("..adapters.out..")
          .layer("Application")
          .definedBy("..application..")
          .layer("Domain")
          .definedBy("..domain..")
          .whereLayer("Bootstrap")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Web")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Outbound")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Web", "Bootstrap")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Application", "Web", "Outbound", "Bootstrap");
}
