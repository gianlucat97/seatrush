package com.seatrush.queue.adapters.out.redis;

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
 * The Lua scripts of the waiting room, loaded once and called by digest (README section 21).
 *
 * <p>Spring sends the body of a script whenever Redis reports it does not know the digest, so
 * loading them at startup is not what makes {@code EVALSHA} work. It is what makes a broken script
 * a deployment failure rather than a failed join during an on-sale: {@code SCRIPT LOAD} compiles
 * the body, so a syntax error is found while the service starts and the service refuses to start.
 *
 * <p>A Redis that is not reachable yet is a different matter and only logged: the scripts travel
 * with the first call that needs them.
 */
@Component
public class QueueScripts {

  private static final Logger log = LoggerFactory.getLogger(QueueScripts.class);

  private final RedisScript<String> join =
      RedisScript.of(new ClassPathResource("redis/join.lua"), String.class);
  private final RedisScript<Long> leave =
      RedisScript.of(new ClassPathResource("redis/leave.lua"), Long.class);
  private final RedisScript<Long> openLobby =
      RedisScript.of(new ClassPathResource("redis/openLobby.lua"), Long.class);

  private final StringRedisTemplate redis;

  public QueueScripts(StringRedisTemplate redis) {
    this.redis = redis;
  }

  public RedisScript<String> join() {
    return join;
  }

  public RedisScript<Long> leave() {
    return leave;
  }

  public RedisScript<Long> openLobby() {
    return openLobby;
  }

  /** Compiles every script on the server, failing the startup if one of them is broken. */
  @EventListener(ApplicationReadyEvent.class)
  public void loadScripts() {
    try {
      for (RedisScript<?> script : List.of(join, leave, openLobby)) {
        String digest =
            redis.execute(
                (RedisCallback<String>)
                    connection ->
                        connection
                            .scriptingCommands()
                            .scriptLoad(
                                script.getScriptAsString().getBytes(StandardCharsets.UTF_8)));
        log.debug("loaded a waiting room script as {}", digest);
      }
      log.info("the three scripts of the waiting room are compiled on Redis");
    } catch (RedisConnectionFailureException notUpYet) {
      log.warn(
          "Redis is not reachable, the waiting room scripts will be sent on first use", notUpYet);
    }
  }
}
