package com.seatrush.messaging;

import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SchemaVersion;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Every message type of README section 10.3, by the name it carries on the wire.
 *
 * <p>The catalog is derived from the sealed hierarchy rather than written out: {@link Message}
 * permits six groups, each group permits its records, and walking that tree reaches exactly the
 * leaves. A new message type is therefore registered by the only act that could introduce it,
 * adding it to a {@code permits} clause, and a type that was renamed cannot be left behind in a
 * list nobody updated.
 */
public final class MessageCatalog {

  private static final Map<String, Class<? extends Message>> BY_NAME = walkSealedHierarchy();

  private MessageCatalog() {}

  /** The wire names of every known type, in the order the hierarchy declares them. */
  public static Set<String> typeNames() {
    return BY_NAME.keySet();
  }

  /** Every known message class. */
  public static Collection<Class<? extends Message>> types() {
    return BY_NAME.values();
  }

  /** The class of a wire name, or empty when this build does not know the name. */
  public static Optional<Class<? extends Message>> find(String type) {
    return Optional.ofNullable(BY_NAME.get(type));
  }

  /**
   * The class of a wire name.
   *
   * @throws UnknownMessageTypeException if the name is not in the catalog
   */
  public static Class<? extends Message> typeOf(String type) {
    return find(type).orElseThrow(() -> new UnknownMessageTypeException(type));
  }

  /** The highest schema version this build understands for a type (see {@link SchemaVersion}). */
  public static int schemaVersionOf(Class<? extends Message> type) {
    SchemaVersion annotation = type.getAnnotation(SchemaVersion.class);
    return annotation == null ? 1 : annotation.value();
  }

  /**
   * The highest schema version this build understands for a wire name.
   *
   * @throws UnknownMessageTypeException if the name is not in the catalog
   */
  public static int schemaVersionOf(String type) {
    return schemaVersionOf(typeOf(type));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Class<? extends Message>> walkSealedHierarchy() {
    Map<String, Class<? extends Message>> leaves = new LinkedHashMap<>();
    Deque<Class<?>> pending = new ArrayDeque<>();
    pending.push(Message.class);
    while (!pending.isEmpty()) {
      Class<?> current = pending.pop();
      Class<?>[] permitted = current.getPermittedSubclasses();
      if (permitted == null || permitted.length == 0) {
        // A leaf: one of the records of section 10.3.
        Class<? extends Message> type = (Class<? extends Message>) current;
        Class<? extends Message> clash = leaves.put(current.getSimpleName(), type);
        if (clash != null) {
          throw new IllegalStateException(
              "two message types share the wire name " + current.getSimpleName());
        }
        continue;
      }
      // Pushed in reverse, so the iteration order follows the permits clauses.
      for (int i = permitted.length - 1; i >= 0; i--) {
        pending.push(permitted[i]);
      }
    }
    return Map.copyOf(leaves);
  }
}
