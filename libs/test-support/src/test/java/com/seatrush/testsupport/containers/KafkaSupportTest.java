package com.seatrush.testsupport.containers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** Covers the part of {@link KafkaSupport} that needs no broker. */
class KafkaSupportTest {

  @Test
  void keepsThePrefixAndAppendsADistinctSuffix() {
    String first = KafkaSupport.uniqueTopic("seat-held");
    String second = KafkaSupport.uniqueTopic("seat-held");

    assertThat(first).startsWith("seat-held-");
    assertThat(second).startsWith("seat-held-");
    assertThat(first).isNotEqualTo(second);
  }

  @Test
  void handsOutNoNameTwiceEvenUnderConcurrentCallers() throws Exception {
    int callers = 500;

    List<Callable<String>> names =
        IntStream.range(0, callers)
            .<Callable<String>>mapToObj(i -> () -> KafkaSupport.uniqueTopic("orders"))
            .toList();

    List<Future<String>> results;
    try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
      results = executor.invokeAll(names);
    }

    List<String> handedOut = new ArrayList<>();
    for (Future<String> result : results) {
      handedOut.add(result.get());
    }
    assertThat(handedOut).hasSize(callers).doesNotHaveDuplicates();
  }

  @Test
  void rejectsAMissingPrefix() {
    assertThatThrownBy(() -> KafkaSupport.uniqueTopic(null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("prefix");
    assertThatThrownBy(() -> KafkaSupport.uniqueTopic("  "))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
