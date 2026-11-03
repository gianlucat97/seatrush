package com.seatrush.gateway.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The three commands that need a turn in the purchase area, and the many requests that do not.
 *
 * <p>Worth this many cases because the failure is silent in one direction: a path that stops
 * matching is a check that stops running, and the request still succeeds. Nothing else in the
 * system would notice.
 */
class PurchaseCommandTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  @ParameterizedTest
  @CsvSource({
    "POST,/api/events/0199c5f0-2222-7000-8000-00000000b002/holds",
    "DELETE,/api/holds/0199c5f0-4444-7000-8000-00000000d004",
    "POST,/api/orders"
  })
  void demandsAnAdmissionOnTheThreeCommandsThatTakeSeats(String method, String path) {
    assertThat(PurchaseCommand.requiresAdmission(method, path)).isTrue();
  }

  /** Every read: the catalog, the seat map, a hold, an order, and the waiting room itself. */
  @ParameterizedTest
  @ValueSource(
      strings = {
        "/api/events",
        "/api/events/0199c5f0-2222-7000-8000-00000000b002",
        "/api/events/0199c5f0-2222-7000-8000-00000000b002/seatmap",
        "/api/holds/0199c5f0-4444-7000-8000-00000000d004",
        "/api/orders/0199c5f0-1111-7000-8000-00000000a001",
        "/api/orders",
        "/api/events/0199c5f0-2222-7000-8000-00000000b002/queue/me"
      })
  void letsEveryReadThroughWithoutOne(String path) {
    assertThat(PurchaseCommand.requiresAdmission("GET", path)).isFalse();
  }

  /**
   * Joining a queue is not a purchase command, which is the one that would be circular: a buyer
   * cannot show an admission they are queueing to be given.
   */
  @Test
  void letsAUserJoinAndLeaveTheQueueWithoutOne() {
    String queue = "/api/events/" + EVENT_ID + "/queue";
    assertThat(PurchaseCommand.requiresAdmission("POST", queue)).isFalse();
    assertThat(PurchaseCommand.requiresAdmission("DELETE", queue)).isFalse();
  }

  @Test
  void readsTheEventOutOfAHoldCommand() {
    assertThat(PurchaseCommand.eventOf("/api/events/" + EVENT_ID + "/holds")).contains(EVENT_ID);
  }

  /**
   * The other two name a hold and an order, whose event is known to the services that own them.
   * Absent here means the gateway checks the admission without binding it to a sale.
   */
  @Test
  void findsNoEventInTheCommandsThatDoNotNameOne() {
    assertThat(PurchaseCommand.eventOf("/api/holds/0199c5f0-4444-7000-8000-00000000d004"))
        .isEmpty();
    assertThat(PurchaseCommand.eventOf("/api/orders")).isEmpty();
  }

  /** An id that is not one is refused downstream; there is nothing to compare here meanwhile. */
  @Test
  void findsNoEventInAPathWhoseEventIsNotAnId() {
    assertThat(PurchaseCommand.eventOf("/api/events/not-an-id/holds")).isEmpty();
  }

  /** A trailing slash is the same request, and a client that sends one must not skip the check. */
  @Test
  void treatsATrailingSlashAsTheSamePath() {
    assertThat(PurchaseCommand.requiresAdmission("POST", "/api/orders/")).isTrue();
    assertThat(PurchaseCommand.requiresAdmission("POST", "/api/events/" + EVENT_ID + "/holds/"))
        .isTrue();
  }

  /** And a path that merely starts the same way is a different endpoint. */
  @Test
  void doesNotDemandOneOnAPathThatOnlyLooksLikeACommand() {
    assertThat(PurchaseCommand.requiresAdmission("POST", "/api/orders/0199c5f0/cancel")).isFalse();
    assertThat(PurchaseCommand.requiresAdmission("POST", "/api/events/" + EVENT_ID + "/holdings"))
        .isFalse();
  }

  @Test
  void answersNoForARequestWithNoMethodOrNoPath() {
    assertThat(PurchaseCommand.requiresAdmission(null, "/api/orders")).isFalse();
    assertThat(PurchaseCommand.requiresAdmission("POST", null)).isFalse();
    assertThat(PurchaseCommand.eventOf(null)).isEmpty();
  }
}
