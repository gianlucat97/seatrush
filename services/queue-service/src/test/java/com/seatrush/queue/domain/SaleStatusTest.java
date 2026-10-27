package com.seatrush.queue.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.EnumSet;
import org.junit.jupiter.api.Test;

/** The two questions this service asks of a status it does not own. */
class SaleStatusTest {

  @Test
  void acceptsWaitingOnlyBeforeAndDuringASale() {
    assertThat(EnumSet.allOf(SaleStatus.class).stream().filter(SaleStatus::acceptsWaiting))
        .containsExactly(SaleStatus.SCHEDULED, SaleStatus.ON_SALE);
  }

  @Test
  void isSellingOnlyWhileOnSale() {
    assertThat(EnumSet.allOf(SaleStatus.class).stream().filter(SaleStatus::selling))
        .containsExactly(SaleStatus.ON_SALE);
  }

  /**
   * A reminder rather than an assertion about behaviour: this vocabulary is inventory's, and a
   * value added there is a value this build drops until it is updated (see the catalog adapter).
   */
  @Test
  void mirrorsTheFiveStatusesInventoryPublishes() {
    assertThat(SaleStatus.values())
        .containsExactly(
            SaleStatus.DRAFT,
            SaleStatus.SCHEDULED,
            SaleStatus.ON_SALE,
            SaleStatus.SOLD_OUT,
            SaleStatus.CLOSED);
  }
}
