package com.seatrush.testsupport.containers;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import org.springframework.test.context.DynamicPropertyRegistry;

/**
 * A {@link DynamicPropertyRegistry} that keeps what was registered, so a test can assert on the
 * property names and on the values the suppliers resolve to without booting a Spring context.
 */
final class RecordingPropertyRegistry implements DynamicPropertyRegistry {

  private final Map<String, Supplier<Object>> properties = new LinkedHashMap<>();

  @Override
  public void add(String name, Supplier<Object> valueSupplier) {
    properties.put(name, valueSupplier);
  }

  Set<String> names() {
    return properties.keySet();
  }

  /** Resolves one registered property, the way the test context would. */
  String valueOf(String name) {
    Supplier<Object> supplier = properties.get(name);
    if (supplier == null) {
      throw new AssertionError("no property named " + name + ", registered: " + names());
    }
    return String.valueOf(supplier.get());
  }
}
