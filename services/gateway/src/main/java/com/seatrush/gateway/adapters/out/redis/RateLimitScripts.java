package com.seatrush.gateway.adapters.out.redis;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.ReactiveStringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The token bucket script, loaded once and called by digest (README section 21).
 *
 * <p>The same argument as the waiting room scripts of queue-service. Spring sends the body whenever
 * Redis reports it does not know the digest, so loading at startup is not what makes {@code
 * EVALSHA} work; it is what makes a broken script a deployment failure rather than a failed
 * purchase during an on-sale. {@code SCRIPT LOAD} compiles the body, so a syntax error is found
 * while the pod starts.
 *
 * <p>A Redis that is not reachable yet is only logged, because the script travels with the first
 * call that needs it, and because a gateway that refused to start without Redis would be a gateway
 * that cannot serve a single read while the store it only needs for limits comes up.
 */
@Component
public class RateLimitScripts {

  private static final Logger log = LoggerFactory.getLogger(RateLimitScripts.class);

  /**
   * The script, declared with a raw {@code List} return type.
   *
   * <p>Lua answers {@code { allowed, retry_after_millis }}, which Redis returns as a multi-bulk
   * reply and Spring hands back as a list of {@code Long}. There is no generic spelling of that in
   * {@link RedisScript#of}, so the raw type is the honest one and {@link RedisTokenBuckets} reads
   * the two positions by index.
   */
  @SuppressWarnings("rawtypes")
  private final RedisScript<List> tokenBucket =
      RedisScript.of(new ClassPathResource("redis/tokenBucket.lua"), List.class);

  private final ReactiveStringRedisTemplate redis;

  public RateLimitScripts(ReactiveStringRedisTemplate redis) {
    this.redis = redis;
  }

  @SuppressWarnings("rawtypes")
  public RedisScript<List> tokenBucket() {
    return tokenBucket;
  }

  /** Compiles the script on the server, so that a broken one is found at startup. */
  @EventListener(ApplicationReadyEvent.class)
  public void loadScripts() {
    ByteBuffer body =
        ByteBuffer.wrap(tokenBucket.getScriptAsString().getBytes(StandardCharsets.UTF_8));
    redis
        .execute(connection -> connection.scriptingCommands().scriptLoad(body))
        .next()
        .doOnNext(digest -> log.info("the token bucket script is compiled on Redis as {}", digest))
        .doOnError(
            notUpYet ->
                log.warn(
                    "Redis is not reachable, the token bucket script will be sent on first use: {}",
                    notUpYet.getMessage()))
        .onErrorComplete()
        .subscribe();
  }
}
