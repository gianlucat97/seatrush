package com.seatrush.invariants;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The Redis key names, written out as literals.
 *
 * <p>The assertions are deliberately whole strings and not built from the same helpers they test.
 * These keys are the one thing the checker and inventory-service have to agree about without
 * sharing any code, so the test has to state what README section 9.2 says rather than restate what
 * the code does.
 */
class SeatKeysTest {

  private static final UUID EVENT = UUID.fromString("0192f000-0000-7000-8000-00000000e001");
  private static final UUID HOLD = UUID.fromString("0192f000-0000-7000-8000-00000000d001");
  private static final UUID USER = UUID.fromString("0192f000-0000-7000-8000-0000000000a1");

  @Test
  void everyKeyOfAnEventCarriesItsHashTag() {
    assertThat(SeatKeys.seats(EVENT)).isEqualTo("seats:{evt:0192f000-0000-7000-8000-00000000e001}");
    assertThat(SeatKeys.version(EVENT))
        .isEqualTo("seats:{evt:0192f000-0000-7000-8000-00000000e001}:version");
    assertThat(SeatKeys.available(EVENT))
        .isEqualTo("seats:{evt:0192f000-0000-7000-8000-00000000e001}:available");
    assertThat(SeatKeys.holdExpiry(EVENT))
        .isEqualTo("holds:{evt:0192f000-0000-7000-8000-00000000e001}:expiry");
    assertThat(SeatKeys.holdCheckout(EVENT))
        .isEqualTo("holds:{evt:0192f000-0000-7000-8000-00000000e001}:checkout");
  }

  @Test
  void theScansMatchTheKeysOfOneEventAndNothingElse() {
    assertThat(SeatKeys.holdPattern(EVENT))
        .isEqualTo("hold:{evt:0192f000-0000-7000-8000-00000000e001}:*");
    assertThat(SeatKeys.userHoldPattern(EVENT))
        .isEqualTo("userhold:{evt:0192f000-0000-7000-8000-00000000e001}:*");
  }

  @Test
  void readsTheIdBackOutOfAKeyAScanReturned() {
    assertThat(SeatKeys.holdIdOf("hold:" + SeatKeys.tag(EVENT) + ":" + HOLD)).isEqualTo(HOLD);
    assertThat(SeatKeys.userIdOf("userhold:" + SeatKeys.tag(EVENT) + ":" + USER)).isEqualTo(USER);
  }
}
