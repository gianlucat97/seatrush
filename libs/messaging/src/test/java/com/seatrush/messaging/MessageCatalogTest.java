package com.seatrush.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.messaging.messages.CommitSeats;
import com.seatrush.messaging.messages.EventSoldOut;
import com.seatrush.messaging.messages.Message;
import com.seatrush.messaging.messages.SampleMessages;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessageCatalogTest {

  /** The table of README section 10.3, written out so the catalog is checked against the spec. */
  private static final List<String> SPECIFIED_TYPES =
      List.of(
          "CommitSeats",
          "ReleaseHold",
          "SeatsCommitted",
          "SeatsCommitFailed",
          "HoldReleased",
          "AuthorizePayment",
          "CapturePayment",
          "VoidPayment",
          "PaymentAuthorized",
          "PaymentCaptured",
          "PaymentVoided",
          "PaymentDeclined",
          "SeatsHeld",
          "SeatsReleased",
          "SeatsSold",
          "EventSoldOut",
          "OrderConfirmed",
          "OrderFailed");

  @Test
  void holdsExactlyTheMessageTypesTheSpecificationLists() {
    assertThat(MessageCatalog.typeNames())
        .hasSize(18)
        .containsExactlyInAnyOrderElementsOf(SPECIFIED_TYPES);
  }

  @Test
  void everyTypeIsALeafOfTheSealedHierarchy() {
    assertThat(MessageCatalog.types())
        .allSatisfy(
            type -> {
              assertThat(Message.class).isAssignableFrom(type);
              assertThat(type.isRecord()).as("%s is a record", type.getSimpleName()).isTrue();
              assertThat(type.getPermittedSubclasses())
                  .as("%s permits nothing", type.getSimpleName())
                  .isNull();
            });
  }

  @Test
  void resolvesAWireNameToItsClass() {
    assertThat(MessageCatalog.typeOf("CommitSeats")).isEqualTo(CommitSeats.class);
    assertThat(MessageCatalog.find("EventSoldOut")).contains(EventSoldOut.class);
  }

  @Test
  void aTypeCarriesTheNameItsInstancesReport() {
    assertThat(SampleMessages.oneOfEach())
        .allSatisfy(
            message ->
                assertThat(MessageCatalog.typeOf(message.type())).isEqualTo(message.getClass()));
  }

  @Test
  void aSampleExistsForEveryTypeInTheCatalog() {
    assertThat(SampleMessages.oneOfEach())
        .extracting(Message::type)
        .containsExactlyInAnyOrderElementsOf(MessageCatalog.typeNames());
  }

  @Test
  void everyTypeStartsAtSchemaVersionOne() {
    assertThat(MessageCatalog.types())
        .allSatisfy(type -> assertThat(MessageCatalog.schemaVersionOf(type)).isEqualTo(1));
    assertThat(MessageCatalog.schemaVersionOf("SeatsHeld")).isEqualTo(1);
  }

  @Test
  void refusesANameItDoesNotKnow() {
    assertThat(MessageCatalog.find("SeatsTeleported")).isEmpty();
    assertThatThrownBy(() -> MessageCatalog.typeOf("SeatsTeleported"))
        .isInstanceOf(UnknownMessageTypeException.class)
        .hasMessageContaining("SeatsTeleported");
    assertThatThrownBy(() -> MessageCatalog.schemaVersionOf("SeatsTeleported"))
        .isInstanceOf(UnknownMessageTypeException.class);
  }

  @Test
  void theCatalogCannotBeChangedByItsCallers() {
    assertThatThrownBy(() -> MessageCatalog.typeNames().clear())
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
