package com.seatrush.queue.adapters.out.redis;

import java.util.List;

/**
 * What {@code admit.lua} answered: who was let in, and the three numbers that describe the door
 * afterwards.
 *
 * <p>{@code admitted} is absent rather than empty when nobody was let in, which is the ordinary
 * case once a queue has drained. The cjson of Redis encodes an empty Lua table as an object and not
 * as an array, so a script sending an empty list would send {@code {}} and nothing could read it;
 * the script leaves the key out and this record reads that as nobody.
 */
record AdmitReply(List<String> admitted, Long head, Integer activeUsers, Integer freedSlots) {

  AdmitReply {
    admitted = admitted == null ? List.of() : List.copyOf(admitted);
  }

  long requireHead() {
    return require(head, "head");
  }

  int requireActiveUsers() {
    return require(activeUsers, "activeUsers");
  }

  int requireFreedSlots() {
    return require(freedSlots, "freedSlots");
  }

  private static <T> T require(T value, String field) {
    if (value == null) {
      throw new IllegalStateException("an admit reply carries no " + field);
    }
    return value;
  }
}
