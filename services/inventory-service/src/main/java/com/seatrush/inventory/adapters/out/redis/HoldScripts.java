package com.seatrush.inventory.adapters.out.redis;

import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The Lua scripts of the hold path, loaded once and called by digest (README section 21).
 *
 * <p>Spring sends the body of a script whenever Redis reports it does not know the digest, so
 * loading them at startup is not what makes {@code EVALSHA} work. It is what makes a broken script
 * a deployment failure instead of a failed hold during an on-sale: {@code SCRIPT LOAD} compiles the
 * body, so a syntax error is found while the service starts and the service refuses to start.
 *
 * <p>A Redis that is not reachable yet is a different matter and only logged: the scripts travel
 * with the first call that needs them.
 */
@Component
public class HoldScripts {

  private static final Logger log = LoggerFactory.getLogger(HoldScripts.class);

  private final RedisScript<String> hold =
      RedisScript.of(new ClassPathResource("redis/hold.lua"), String.class);
  private final RedisScript<String> release =
      RedisScript.of(new ClassPathResource("redis/release.lua"), String.class);
  private final RedisScript<String> expire =
      RedisScript.of(new ClassPathResource("redis/expire.lua"), String.class);
  private final RedisScript<String> freeze =
      RedisScript.of(new ClassPathResource("redis/freeze.lua"), String.class);
  private final RedisScript<String> markSold =
      RedisScript.of(new ClassPathResource("redis/markSold.lua"), String.class);

  private final StringRedisTemplate redis;

  public HoldScripts(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public RedisScript<String> hold() {
    return hold;
  }

  public RedisScript<String> release() {
    return release;
  }

  public RedisScript<String> expire() {
    return expire;
  }

  public RedisScript<String> freeze() {
    return freeze;
  }

  public RedisScript<String> markSold() {
    return markSold;
  }

  /** Compiles every script on the server, failing the startup if one of them is broken. */
  @EventListener(ApplicationReadyEvent.class)
  public void loadScripts() {
    try {
      for (RedisScript<String> script : List.of(hold, release, expire, freeze, markSold)) {
        String digest =
            redis.execute(
                (RedisCallback<String>)
                    connection ->
                        connection
                            .scriptingCommands()
                            .scriptLoad(
                                script.getScriptAsString().getBytes(StandardCharsets.UTF_8)));
        log.debug("loaded a hold path script as {}", digest);
      }
      log.info("the five scripts of the hold path are compiled on Redis");
    } catch (RedisConnectionFailureException notUpYet) {
      log.warn("Redis is not reachable, the hold path scripts will be sent on first use", notUpYet);
    }
  }
}
