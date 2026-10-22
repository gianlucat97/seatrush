package com.seatrush.messaging.messages;

import com.seatrush.common.money.Money;
import java.util.List;
import java.util.UUID;

/**
 * One instance of every message type of README section 10.3.
 *
 * <p>The round trip tests iterate over this list, and {@code MessageCatalogTest} asserts that it
 * covers the whole catalog: a message type added without a sample fails the build here rather than
 * reaching production with its serialization untested.
 */
public final class SampleMessages {

  public static final UUID ORDER_ID = UUID.fromString("0199c5f0-1111-7000-8000-00000000a001");
  public static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  public static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  public static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  public static final UUID OPERATION_ID = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");
  public static final List<String> SEAT_IDS = List.of("A-12-7", "A-12-8");

  private SampleMessages() {}

  public static List<Message> oneOfEach() {
    return List.of(
        new CommitSeats(ORDER_ID, EVENT_ID, HOLD_ID, SEAT_IDS),
        new ReleaseHold(ORDER_ID, EVENT_ID, HOLD_ID),
        new SeatsCommitted(ORDER_ID, EVENT_ID, SEAT_IDS),
        new SeatsCommitFailed(ORDER_ID, EVENT_ID, "seat A-12-7 is already sold", SEAT_IDS),
        new HoldReleased(ORDER_ID, HOLD_ID),
        new AuthorizePayment(ORDER_ID, USER_ID, Money.of(9000L, "EUR")),
        new CapturePayment(ORDER_ID),
        new VoidPayment(ORDER_ID),
        new PaymentAuthorized(ORDER_ID, OPERATION_ID),
        new PaymentCaptured(ORDER_ID, OPERATION_ID),
        new PaymentVoided(ORDER_ID, OPERATION_ID),
        new PaymentDeclined(ORDER_ID, "insufficient funds"),
        new SeatsHeld(EVENT_ID, SEAT_IDS, 41L, HOLD_ID),
        new SeatsReleased(EVENT_ID, SEAT_IDS, 42L, HOLD_ID),
        new SeatsSold(EVENT_ID, SEAT_IDS, 43L, HOLD_ID),
        new EventSoldOut(EVENT_ID),
        new OrderConfirmed(ORDER_ID, USER_ID, EVENT_ID),
        new OrderFailed(ORDER_ID, USER_ID, EVENT_ID, FailureReason.PAYMENT_DECLINED));
  }

  public static SeatsHeld seatsHeld() {
    return new SeatsHeld(EVENT_ID, SEAT_IDS, 7L, HOLD_ID);
  }

  public static CommitSeats commitSeats() {
    return new CommitSeats(ORDER_ID, EVENT_ID, HOLD_ID, SEAT_IDS);
  }

  public static AuthorizePayment authorizePayment() {
    return new AuthorizePayment(ORDER_ID, USER_ID, Money.of(4500L, "EUR"));
  }

  public static SeatsCommitted seatsCommitted() {
    return new SeatsCommitted(ORDER_ID, EVENT_ID, SEAT_IDS);
  }
}
