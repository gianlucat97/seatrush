package com.seatrush.common.id;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.time.Instant;
import java.util.UUID;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;

class UuidV7Test {

  private static final Instant ON_SALE = Instant.parse("2026-10-21T19:07:38Z");

  private final RandomGenerator random = new java.util.Random(42L);

  @Test
  void stampsVersion7AndTheRfcVariant() {
    UUID id = UuidV7.from(ON_SALE, random);

    assertThat(id.version()).isEqualTo(7);
    assertThat(id.variant()).isEqualTo(2);
  }

  @Test
  void keepsTheCreationInstantToTheMillisecond() {
    UUID id = UuidV7.from(ON_SALE, random);

    assertThat(UuidV7.timestampOf(id)).isEqualTo(ON_SALE);
  }

  @Test
  void ordersLexicographicallyByTime() {
    UUID earlier = UuidV7.from(ON_SALE, random);
    UUID later = UuidV7.from(ON_SALE.plusMillis(1), random);
    UUID muchLater = UuidV7.from(ON_SALE.plusSeconds(3600), random);

    assertThat(earlier.toString()).isLessThan(later.toString());
    assertThat(later.toString()).isLessThan(muchLater.toString());
  }

  @Test
  void differsEvenWithinTheSameMillisecond() {
    UUID first = UuidV7.from(ON_SALE, random);
    UUID second = UuidV7.from(ON_SALE, random);

    assertThat(first).isNotEqualTo(second);
    assertThat(UuidV7.timestampOf(first)).isEqualTo(UuidV7.timestampOf(second));
  }

  @Test
  void acceptsTheWholeTimestampRange() {
    assertThat(UuidV7.timestampOf(UuidV7.from(Instant.EPOCH, random))).isEqualTo(Instant.EPOCH);

    Instant lastRepresentable = Instant.ofEpochMilli((1L << 48) - 1);
    assertThat(UuidV7.timestampOf(UuidV7.from(lastRepresentable, random)))
        .isEqualTo(lastRepresentable);
  }

  @Test
  void rejectsInstantsOutsideTheTimestampRange() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> UuidV7.from(Instant.EPOCH.minusMillis(1), random))
        .withMessageContaining("outside the UUIDv7 timestamp range");
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> UuidV7.from(Instant.ofEpochMilli(1L << 48), random));
  }

  @Test
  void refusesToReadTheTimestampOfOtherUuidVersions() {
    assertThatExceptionOfType(IllegalArgumentException.class)
        .isThrownBy(() -> UuidV7.timestampOf(UUID.randomUUID()))
        .withMessageContaining("version is 4");
  }
}
