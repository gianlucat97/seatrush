package com.seatrush.testsupport.containers;

import org.testcontainers.utility.DockerImageName;

/**
 * The container images the test suite runs against, pinned in one place.
 *
 * <p>Tags are pinned to a minor line rather than a digest: tests must break when the version the
 * production stack targets moves, not when a patch release is published. The versions mirror the
 * tech stack of the README, so a test never silently validates a Redis or Postgres older than the
 * one the deployment uses.
 */
public final class ContainerImages {

  /** PostgreSQL 17, the version the Flyway migrations and the seat commit guard target. */
  public static final DockerImageName POSTGRES = DockerImageName.parse("postgres:17-alpine");

  /** Redis 7.4, the version whose Lua scripting and Streams behaviour the hold path relies on. */
  public static final DockerImageName REDIS = DockerImageName.parse("redis:7.4-alpine");

  /**
   * Apache Kafka 4.1 in KRaft mode. The 4.x line has no ZooKeeper at all, which is what the
   * deployment assumes, and it matches the kafka-clients version Spring Boot manages.
   */
  public static final DockerImageName KAFKA = DockerImageName.parse("apache/kafka:4.1.0");

  private ContainerImages() {}
}
