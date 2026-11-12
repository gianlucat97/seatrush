package com.seatrush.queue.adapters.out.rest;

import static org.assertj.core.api.Assertions.assertThat;

import com.seatrush.queue.domain.SaleStatus;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Reading an event out of another service's response.
 *
 * <p>The interesting cases are the ones where inventory says something this build was not written
 * for. The status is the vocabulary inventory owns, so it is the field that can grow a value; a
 * waiting room that treated an unknown one as open for business would queue buyers in front of a
 * sale that does not exist.
 */
class CatalogEventResponseTest {

  private static final UUID EVENT_ID = UUID.fromString("0199c5f0-2222-7000-8000-00000000b002");
  private static final Instant ON_SALE_AT = Instant.parse("2026-06-01T10:00:00Z");

  @Test
  void readsAnEventItUnderstands() {
    CatalogEventResponse response =
        new CatalogEventResponse(EVENT_ID, "A sale", ON_SALE_AT, "ON_SALE");

    assertThat(response.toSaleEvent())
        .isNotNull()
        .satisfies(
            event -> {
              assertThat(event.id()).isEqualTo(EVENT_ID);
              assertThat(event.name()).isEqualTo("A sale");
              assertThat(event.onSaleAt()).isEqualTo(ON_SALE_AT);
              assertThat(event.status()).isEqualTo(SaleStatus.ON_SALE);
            });
  }

  @Test
  void readsEveryStatusInventoryPublishesToday() {
    for (SaleStatus status : SaleStatus.values()) {
      CatalogEventResponse response =
          new CatalogEventResponse(EVENT_ID, "A sale", ON_SALE_AT, status.name());

      assertThat(response.toSaleEvent()).as("reading %s", status).isNotNull();
    }
  }

  /** A status from a newer inventory: one event left out, not one list that fails to parse. */
  @Test
  void refusesAStatusThisBuildDoesNotKnow() {
    CatalogEventResponse response =
        new CatalogEventResponse(EVENT_ID, "A sale", ON_SALE_AT, "PRESALE");

    assertThat(response.toSaleEvent()).isNull();
  }

  @Test
  void refusesAnEventWithAFieldMissing() {
    assertThat(new CatalogEventResponse(null, "A sale", ON_SALE_AT, "ON_SALE").toSaleEvent())
        .isNull();
    assertThat(new CatalogEventResponse(EVENT_ID, null, ON_SALE_AT, "ON_SALE").toSaleEvent())
        .isNull();
    assertThat(new CatalogEventResponse(EVENT_ID, "A sale", null, "ON_SALE").toSaleEvent())
        .isNull();
    assertThat(new CatalogEventResponse(EVENT_ID, "A sale", ON_SALE_AT, null).toSaleEvent())
        .isNull();
  }
}
