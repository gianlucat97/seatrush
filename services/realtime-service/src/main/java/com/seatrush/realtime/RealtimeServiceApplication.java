package com.seatrush.realtime;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The realtime service: the live half of the seat map (README section 6.6).
 *
 * <p>What is here is one endpoint and one listener. Every seat change of every sale arrives from
 * {@code inventory.seat-events} under a consumer group this pod does not share, is batched for a
 * fifth of a second, and leaves as one delta per sale to the clients watching that sale here. The
 * snapshot those deltas are applied to belongs to inventory, which owns the seat map; this service
 * owns nothing and only says what moved.
 *
 * <p>It holds no database, no Redis and no secret. The whole of its state is the connections it is
 * holding and the last fraction of a second of each sale it is following, so a pod can be killed at
 * any moment and the only consequence is that its clients reconnect, reload a snapshot and carry on
 * (README section 16). That is also what makes this the easiest service in the system to scale: add
 * a pod and it serves its own clients from the same stream of events.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// The batch window and the keep-alive are both passes on a schedule, on every pod at once. What
// makes that safe is that a pod only ever writes to its own connections.
@EnableScheduling
public class RealtimeServiceApplication {

  public static void main(String[] args) {
    SpringApplication.run(RealtimeServiceApplication.class, args);
  }
}
