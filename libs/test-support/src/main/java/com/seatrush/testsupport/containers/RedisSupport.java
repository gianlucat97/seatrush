package com.seatrush.testsupport.containers;

import java.io.IOException;
import java.io.UncheckedIOException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;

/**
 * The Redis container shared by every test in the JVM.
 *
 * <p>One container is started on first use and left running until the JVM exits, where Ryuk removes
 * it. A container per test class would dominate the runtime of a suite whose Lua script and
 * concurrency tests all need Redis, and nothing in those tests depends on a fresh server: {@link
 * #flushAll()} gives a clean keyspace far faster than a restart.
 *
 * <p>There is no Testcontainers module for Redis, so this is a plain {@link GenericContainer}.
 */
public final class RedisSupport {

  /** The port Redis listens on inside the container. */
  public static final int PORT = 6379;

  private RedisSupport() {}

  /** The shared container, started on the first call. */
  public static GenericContainer<?> container() {
    return Holder.CONTAINER;
  }

  public static String host() {
    return container().getHost();
  }

  /** The port on the host that forwards to {@link #PORT}; a fresh one on every JVM run. */
  public static int port() {
    return container().getMappedPort(PORT);
  }

  public static String uri() {
    return "redis://" + host() + ":" + port();
  }

  /** Points {@code spring.data.redis.*} of a Spring test context at the shared container. */
  public static void registerProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.data.redis.host", RedisSupport::host);
    registry.add("spring.data.redis.port", RedisSupport::port);
  }

  /**
   * Empties every keyspace, which is how a test gets an isolated Redis given a shared container.
   *
   * <p>The script cache survives: {@code FLUSHALL} drops keys, not the bodies loaded by {@code
   * SCRIPT LOAD}, so a service that resolved its SHA digests at startup keeps working across tests.
   */
  public static void flushAll() {
    redisCli("flushall");
  }

  /**
   * Runs {@code redis-cli} inside the container and returns its trimmed output.
   *
   * <p>Going through the container instead of a client keeps this module free of a Redis driver:
   * each service brings its own, and the driver under test should not be the one setting up the
   * fixture.
   *
   * @throws IllegalStateException if the command exits non-zero
   */
  public static String redisCli(String... arguments) {
    String[] command = new String[arguments.length + 1];
    command[0] = "redis-cli";
    System.arraycopy(arguments, 0, command, 1, arguments.length);
    try {
      ExecResult result = container().execInContainer(command);
      if (result.getExitCode() != 0) {
        throw new IllegalStateException(
            "redis-cli %s exited with %d: %s"
                .formatted(String.join(" ", arguments), result.getExitCode(), result.getStderr()));
      }
      return result.getStdout().strip();
    } catch (IOException e) {
      throw new UncheckedIOException("could not run redis-cli in the container", e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted while running redis-cli", e);
    }
  }

  /** Holds the container so that it starts on first access and only once, without locking. */
  private static final class Holder {

    private static final GenericContainer<?> CONTAINER = start();

    private static GenericContainer<?> start() {
      // The port being open is not enough: Redis accepts connections while still loading, so
      // wait for a server that actually answers a command.
      GenericContainer<?> container =
          new GenericContainer<>(ContainerImages.REDIS)
              .withExposedPorts(PORT)
              .waitingFor(Wait.forSuccessfulCommand("redis-cli ping"));
      container.start();
      return container;
    }
  }
}
