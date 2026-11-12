package com.seatrush.load;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * The two presets and the knobs that override them.
 *
 * <p>The preset assertions are the only place the figures of README section 18.1 are written down
 * twice, which is the point: a benchmark whose size drifted from the scenario it claims to run
 * would publish results about something else, and nothing else in the build would notice.
 */
class LoadProfileTest {

  /** Every property this reads, cleared after each test so one cannot leak into the next. */
  private static final List<String> PROPERTIES =
      List.of(
          "seatrush.load.profile",
          "seatrush.load.baseUrl",
          "seatrush.load.sections",
          "seatrush.load.rows",
          "seatrush.load.seatsPerRow",
          "seatrush.load.lobbyUsers",
          "seatrush.load.arrivingUsers",
          "seatrush.load.spectators",
          "seatrush.load.watchers",
          "seatrush.load.hotSectionShare",
          "seatrush.load.abandonRate",
          "seatrush.load.grace",
          "seatrush.load.watchFor",
          "seatrush.load.assertTargets",
          "seatrush.load.seed");

  @AfterEach
  void clearTheProperties() {
    PROPERTIES.forEach(System::clearProperty);
  }

  @Test
  void theFullProfileIsTheScenarioOfSection18() {
    LoadProfile full = LoadProfile.full();

    assertThat(full.seatCount()).isEqualTo(20_000);
    assertThat(full.sections()).isEqualTo(10);
    assertThat(full.lobbyUsers()).isEqualTo(50_000);
    assertThat(full.arrivingUsers()).isEqualTo(150_000);
    assertThat(full.buyers()).isEqualTo(200_000);
    assertThat(full.arrivalWindow()).isEqualTo(Duration.ofSeconds(60));
    assertThat(full.spectators()).isEqualTo(2_000);
    assertThat(full.maxSeatsPerHold()).isEqualTo(4);
    // The first try plus the three retries section 18.1 allows.
    assertThat(full.holdAttempts()).isEqualTo(4);
    assertThat(full.abandonRate()).isEqualTo(0.10);
    assertThat(full.hotSectionShare()).isEqualTo(0.70);
    assertThat(full.hotSections()).isEqualTo(2);
    // The benchmark is the run the targets of section 15 are about, so it is the run they gate.
    assertThat(full.assertTargets()).isTrue();
  }

  @Test
  void theSmallProfileIsTheSameShapeAtAHundredthOfTheSize() {
    LoadProfile small = LoadProfile.small();

    assertThat(small.seatCount()).isEqualTo(2_000);
    assertThat(small.buyers()).isEqualTo(5_000);
    assertThat(small.sections()).isEqualTo(LoadProfile.full().sections());
    assertThat(small.hotSections()).isEqualTo(LoadProfile.full().hotSections());
    assertThat(small.hotSectionShare()).isEqualTo(LoadProfile.full().hotSectionShare());
    assertThat(small.abandonRate()).isEqualTo(LoadProfile.full().abandonRate());
    // Demand still outruns the venue by an order of magnitude, which is the property that matters.
    assertThat(small.buyers()).isGreaterThan(small.seatCount() * 2);
    assertThat(small.assertTargets()).isFalse();
  }

  @Test
  void bothProfilesCanAskForEveryBlockSizeInEveryRow() {
    assertThat(LoadProfile.small().seatsPerRow())
        .isGreaterThanOrEqualTo(LoadProfile.small().maxSeatsPerHold());
    assertThat(LoadProfile.full().seatsPerRow())
        .isGreaterThanOrEqualTo(LoadProfile.full().maxSeatsPerHold());
  }

  @Test
  void theDefaultIsTheProfileALaptopCanRun() {
    assertThat(LoadProfile.fromSystemProperties().seatCount())
        .isEqualTo(LoadProfile.small().seatCount());
  }

  @Test
  void aPropertyOverridesOneKnobOfThePresetAndNothingElse() {
    System.setProperty("seatrush.load.profile", "full");
    System.setProperty("seatrush.load.spectators", "7");

    LoadProfile profile = LoadProfile.fromSystemProperties();

    assertThat(profile.spectators()).isEqualTo(7);
    assertThat(profile.buyers()).isEqualTo(LoadProfile.full().buyers());
  }

  @Test
  void readsEveryShapeOfKnob() {
    System.setProperty("seatrush.load.baseUrl", "http://gateway.internal:8080");
    System.setProperty("seatrush.load.watchFor", "90s");
    System.setProperty("seatrush.load.abandonRate", "0.25");
    System.setProperty("seatrush.load.assertTargets", "true");
    System.setProperty("seatrush.load.seed", "1234");

    LoadProfile profile = LoadProfile.fromSystemProperties();

    assertThat(profile.baseUrl()).isEqualTo("http://gateway.internal:8080");
    assertThat(profile.watchFor()).isEqualTo(Duration.ofSeconds(90));
    assertThat(profile.abandonRate()).isEqualTo(0.25);
    assertThat(profile.assertTargets()).isTrue();
    assertThat(profile.seed()).isEqualTo(1234);
  }

  @Test
  void refusesAProfileItDoesNotHave() {
    System.setProperty("seatrush.load.profile", "enormous");

    assertThatThrownBy(LoadProfile::fromSystemProperties)
        .hasMessageContaining("unknown load profile: enormous");
  }

  @Test
  void refusesAKnobThatDoesNotParse() {
    System.setProperty("seatrush.load.spectators", "lots");
    assertThatThrownBy(LoadProfile::fromSystemProperties)
        .hasMessageContaining("seatrush.load.spectators is not a number");
    System.clearProperty("seatrush.load.spectators");

    System.setProperty("seatrush.load.watchFor", "a while");
    assertThatThrownBy(LoadProfile::fromSystemProperties)
        .hasMessageContaining("seatrush.load.watchFor is not a duration");
    System.clearProperty("seatrush.load.watchFor");

    System.setProperty("seatrush.load.abandonRate", "2");
    assertThatThrownBy(LoadProfile::fromSystemProperties)
        .hasMessageContaining("seatrush.load.abandonRate is a share of 1");
  }

  @Test
  void saysWhatTheRunIsBeforeItSaysHowItWent() {
    String description = LoadProfile.small().describe();

    assertThat(description)
        .contains("2000 seats in 10 sections of 10 by 20")
        .contains("1500 waiting when the sale opens")
        .contains("10% abandon after holding");
  }
}
