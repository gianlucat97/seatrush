package com.seatrush.testsupport.containers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.EnabledIfDockerAvailable;

@EnabledIfDockerAvailable
class RedisSupportIT {

  @BeforeEach
  void emptyTheKeyspace() {
    RedisSupport.flushAll();
  }

  @Test
  void startsTheRedisVersionTheStackTargets() {
    assertThat(RedisSupport.redisCli("info", "server")).contains("redis_version:7.4");
  }

  @Test
  void answersCommands() {
    assertThat(RedisSupport.redisCli("ping")).isEqualTo("PONG");
  }

  @Test
  void exposesTheMappedPortAndAUriBuiltFromIt() {
    assertThat(RedisSupport.port()).isNotEqualTo(RedisSupport.PORT).isPositive();
    assertThat(RedisSupport.uri())
        .isEqualTo("redis://" + RedisSupport.host() + ":" + RedisSupport.port());
  }

  @Test
  void everyCallerGetsTheSameContainer() {
    assertThat(RedisSupport.container()).isSameAs(RedisSupport.container());
    assertThat(RedisSupport.container().isRunning()).isTrue();
  }

  @Test
  void flushAllDropsTheKeysOfAnEarlierTest() {
    RedisSupport.redisCli("set", "seat:A-12", "HELD");
    assertThat(RedisSupport.redisCli("get", "seat:A-12")).isEqualTo("HELD");

    RedisSupport.flushAll();

    assertThat(RedisSupport.redisCli("dbsize")).isEqualTo("0");
  }

  @Test
  void flushAllKeepsTheScriptCache() {
    String digest = RedisSupport.redisCli("script", "load", "return 41 + 1");

    RedisSupport.flushAll();

    assertThat(RedisSupport.redisCli("evalsha", digest, "0")).isEqualTo("42");
  }

  @Test
  void aFailingCommandIsReportedWithItsOutput() {
    assertThatThrownBy(() -> RedisSupport.redisCli("--unknown-flag"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("redis-cli --unknown-flag");
  }

  @Test
  void registersTheSpringRedisProperties() {
    RecordingPropertyRegistry registry = new RecordingPropertyRegistry();

    RedisSupport.registerProperties(registry);

    assertThat(registry.names())
        .containsExactly("spring.data.redis.host", "spring.data.redis.port");
    assertThat(registry.valueOf("spring.data.redis.host")).isEqualTo(RedisSupport.host());
    assertThat(registry.valueOf("spring.data.redis.port"))
        .isEqualTo(String.valueOf(RedisSupport.port()));
  }
}
