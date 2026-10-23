package com.seatrush.payment.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.Architectures.layeredArchitecture;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * The layering rules of README sections 17 and 20, enforced instead of described.
 *
 * <p>The one that matters most here is the first: the provider simulator is an adapter, and the
 * randomness and the sleeping in it must never leak into the domain or the use case. What decides
 * whether a payment is recorded has to stay deterministic and testable without a dice roll.
 */
@AnalyzeClasses(
    packages = "com.seatrush.payment",
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
   * The use cases orchestrate and own the transaction, so they see Spring's transaction and
   * stereotype annotations; nothing of the web or of JDBC belongs to them.
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
              "org.springframework.jdbc..",
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
              "com.seatrush.queue..",
              "com.seatrush.gateway..",
              "com.seatrush.realtime..");

  @ArchTest
  static final ArchRule theLayersPointInwards =
      layeredArchitecture()
          .consideringOnlyDependenciesInLayers()
          .layer("Bootstrap")
          .definedBy("com.seatrush.payment")
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
