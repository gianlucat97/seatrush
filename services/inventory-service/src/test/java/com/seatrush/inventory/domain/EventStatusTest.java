package com.seatrush.inventory.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

class EventStatusTest {

  @Test
  void theLifecycleRunsForward() {
    assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.SCHEDULED)).isTrue();
    assertThat(EventStatus.SCHEDULED.canTransitionTo(EventStatus.ON_SALE)).isTrue();
    assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.SOLD_OUT)).isTrue();
    assertThat(EventStatus.SOLD_OUT.canTransitionTo(EventStatus.CLOSED)).isTrue();
  }

  @Test
  void anEventCanBeCancelledFromAnyStateThatIsNotOver() {
    assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.CLOSED)).isTrue();
    assertThat(EventStatus.SCHEDULED.canTransitionTo(EventStatus.CLOSED)).isTrue();
    assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.CLOSED)).isTrue();
  }

  @Test
  void nothingGoesBackward() {
    assertThat(EventStatus.SCHEDULED.canTransitionTo(EventStatus.DRAFT)).isFalse();
    assertThat(EventStatus.ON_SALE.canTransitionTo(EventStatus.SCHEDULED)).isFalse();
    assertThat(EventStatus.SOLD_OUT.canTransitionTo(EventStatus.ON_SALE)).isFalse();
  }

  @Test
  void theSaleIsNotSkipped() {
    assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.ON_SALE)).isFalse();
    assertThat(EventStatus.DRAFT.canTransitionTo(EventStatus.SOLD_OUT)).isFalse();
    assertThat(EventStatus.SCHEDULED.canTransitionTo(EventStatus.SOLD_OUT)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(EventStatus.class)
  void closedIsFinalAndNoStatusBecomesItself(EventStatus status) {
    assertThat(EventStatus.CLOSED.canTransitionTo(status)).isFalse();
    assertThat(status.canTransitionTo(status)).isFalse();
  }

  @ParameterizedTest
  @EnumSource(EventStatus.class)
  void onlyAnEventOnSaleAcceptsHolds(EventStatus status) {
    assertThat(status.acceptsHolds()).isEqualTo(status == EventStatus.ON_SALE);
  }

  @Test
  void theStatusesAreTheOnesTheSchemaAllows() {
    assertThat(List.of(EventStatus.values()))
        .containsExactly(
            EventStatus.DRAFT,
            EventStatus.SCHEDULED,
            EventStatus.ON_SALE,
            EventStatus.SOLD_OUT,
            EventStatus.CLOSED);
  }
}
