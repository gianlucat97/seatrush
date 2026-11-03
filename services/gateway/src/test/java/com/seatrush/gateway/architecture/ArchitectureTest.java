package com.seatrush.gateway.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rules of README sections 17 and 20, enforced instead of described.
 *
 * <p>The gateway has less domain than any other service here, and that is the point of checking:
 * the one rule it owns, which requests may only be made from the purchase area, is worth keeping
 * out of reach of the framework so that it stays a question anybody can ask without a server.
 */
@AnalyzeClasses(
    packages = "com.seatrush.gateway",
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
              "reactor..",
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
   * The use cases orchestrate; nothing of the transport belongs to them. Reactor is on the list
   * because this service is reactive: a use case that returned a {@code Mono} would be a use case
   * shaped by how its caller happens to serve requests.
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
              "org.springframework.cloud..",
              "reactor..",
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
              "com.seatrush.queue..",
              "com.seatrush.realtime..");

  @ArchTest
  static final ArchRule theLayersPointInwards =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Bootstrap")
          .definedBy("com.seatrush.gateway")
          .layer("Web")
          .definedBy("..adapters.in..")
          .layer("Application")
          .definedBy("..application..")
          .layer("Domain")
          .definedBy("..domain..")
          .whereLayer("Bootstrap")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Web")
          .mayNotBeAccessedByAnyLayer()
          .whereLayer("Application")
          .mayOnlyBeAccessedByLayers("Web", "Bootstrap")
          .whereLayer("Domain")
          .mayOnlyBeAccessedByLayers("Application", "Web", "Bootstrap");
}
