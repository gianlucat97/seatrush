package com.seatrush.inventory.adapters.out.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.seatrush.inventory.domain.SeatStatus;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/** The contract between this service and the Lua scripts that will share these keys and values. */
class SeatKeysAndEncodingTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");

  @Nested
  @DisplayName("keys")
  class Keys {

    @Test
    void areTheOnesTheSpecificationNames() {
      assertThat(SeatKeys.seats(EVENT_ID)).isEqualTo("seats:{evt:" + EVENT_ID + "}");
      assertThat(SeatKeys.version(EVENT_ID)).isEqualTo("seats:{evt:" + EVENT_ID + "}:version");
      assertThat(SeatKeys.available(EVENT_ID)).isEqualTo("seats:{evt:" + EVENT_ID + "}:available");
    }

    @Test
    void shareOneHashTagSoOneEventLivesOnOneSlot() {
      String tag = SeatKeys.tag(EVENT_ID);

      assertThat(SeatKeys.seats(EVENT_ID)).contains(tag);
      assertThat(SeatKeys.version(EVENT_ID)).contains(tag);
      assertThat(SeatKeys.available(EVENT_ID)).contains(tag);
      assertThat(tag).startsWith("{").endsWith("}");
    }

    @Test
    void separateTwoEvents() {
      UUID other = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");

      assertThat(SeatKeys.seats(EVENT_ID)).isNotEqualTo(SeatKeys.seats(other));
    }
  }

  @Nested
  @DisplayName("seat values")
  class Values {

    @Test
    void anAvailableSeatIsAAndASoldSeatIsS() {
      assertThat(SeatEncoding.statusOf("A")).isEqualTo(SeatStatus.AVAILABLE);
      assertThat(SeatEncoding.statusOf("S")).isEqualTo(SeatStatus.SOLD);
    }

    @Test
    void aHeldSeatCarriesTheHoldThatOwnsIt() {
      String holdId = "0199c5f0-4444-7000-8000-00000000d004";

      String value = SeatEncoding.held(holdId);

      assertThat(value).isEqualTo("H:" + holdId);
      assertThat(SeatEncoding.statusOf(value)).isEqualTo(SeatStatus.HELD);
      assertThat(SeatEncoding.holdIdOf(value)).isEqualTo(holdId);
    }

    @Test
    void onlyAHeldSeatHasAHold() {
      assertThat(SeatEncoding.holdIdOf("A")).isNull();
      assertThat(SeatEncoding.holdIdOf("S")).isNull();
      assertThat(SeatEncoding.holdIdOf(null)).isNull();
    }

    @Test
    void aValueNobodyWroteIsAFailureAndNotASilentAvailableSeat() {
      assertThatThrownBy(() -> SeatEncoding.statusOf("X"))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("unknown seat state");
      assertThatThrownBy(() -> SeatEncoding.statusOf(null))
          .isInstanceOf(IllegalStateException.class);
    }
  }
}
