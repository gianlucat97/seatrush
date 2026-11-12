package com.seatrush.order.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * The fingerprint that decides whether two requests with one idempotency key are the same request
 * (README section 9.4).
 */
class StartCheckoutCommandTest {

  private static final UUID USER_ID = UUID.fromString("0199c5f0-3333-7000-8000-00000000c003");
  private static final UUID HOLD_ID = UUID.fromString("0199c5f0-4444-7000-8000-00000000d004");
  private static final UUID KEY = UUID.fromString("0199c5f0-5555-7000-8000-00000000e005");

  @Test
  void isTheSameForTheSameHold() {
    assertThat(command(HOLD_ID).requestHash()).isEqualTo(command(HOLD_ID).requestHash());
  }

  @Test
  void differsForADifferentHold() {
    assertThat(command(HOLD_ID).requestHash())
        .isNotEqualTo(command(UUID.randomUUID()).requestHash());
  }

  /**
   * Stable across runs, because it is stored in a column and compared against on a retry that may
   * reach a different pod of a different build.
   */
  @Test
  void isAFixedValueAndNotAnObjectHash() {
    assertThat(command(HOLD_ID).requestHash())
        .isEqualTo("8be8792d72ac78ceaddc1ac9e34b6e60a615dd16116cf0a34be7bc7ceb07defe");
  }

  @Test
  void refusesToBeBuiltWithoutWhatItNeeds() {
    assertThatThrownBy(() -> new StartCheckoutCommand(null, HOLD_ID, KEY))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StartCheckoutCommand(USER_ID, null, KEY))
        .isInstanceOf(NullPointerException.class);
    assertThatThrownBy(() -> new StartCheckoutCommand(USER_ID, HOLD_ID, null))
        .isInstanceOf(NullPointerException.class);
  }

  private static StartCheckoutCommand command(UUID holdId) {
    return new StartCheckoutCommand(USER_ID, holdId, KEY);
  }
}
