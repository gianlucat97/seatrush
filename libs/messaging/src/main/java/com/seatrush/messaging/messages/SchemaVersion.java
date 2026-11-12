package com.seatrush.messaging.messages;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The schema version of one message type, defaulting to 1 when absent.
 *
 * <p>The version belongs to the message, not to the catalog: adding a field to {@code OrderFailed}
 * must not invalidate every {@code SeatsHeld} in flight. A consumer that receives a version higher
 * than the one it was built against cannot know what the extra field means, so it refuses the
 * message rather than acting on half of it (see {@link com.seatrush.messaging.MessageCodec}).
 *
 * <p>Bump it only for a change a consumer must notice: a field whose meaning changed, or one it now
 * has to read. A new optional field that old consumers may ignore does not need a new version.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface SchemaVersion {

  /** The version, starting at 1. */
  int value();
}
