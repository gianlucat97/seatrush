/**
 * The two things this page does with an instant it was given once.
 *
 * Both of them exist because of the same property of this system: the API answers a moment and
 * then says nothing more about it. An admission expires at a time, a hold expires at a time, and
 * nothing arrives when either passes. The page has to be the thing that notices, so counting down
 * is not decoration here but the only way a buyer learns their turn or their seats are gone before
 * a request is refused.
 *
 * Shared rather than duplicated because the waiting room and the checkout now count down one each,
 * and a hold showing `9:60` while an admission shows `10:00` would be two bugs instead of one.
 */

/** How many whole seconds are left until an instant, never below zero. */
export function secondsLeft(expiresAt: string, now: number): number {
  const parsed = Date.parse(expiresAt);
  if (Number.isNaN(parsed)) {
    return 0;
  }
  return Math.max(0, Math.ceil((parsed - now) / 1000));
}

/**
 * A duration as a person reads one.
 *
 * Minutes and seconds up to an hour, then hours and minutes, because the numbers this shows are a
 * hold that lasts five minutes, an admission that lasts ten, and a queue that can be an hour long.
 * Seconds in an estimate of an hour would be precision the estimate does not have.
 */
export function formatDuration(seconds: number): string {
  const whole = Math.max(0, Math.floor(seconds));
  if (whole < 3600) {
    const minutes = Math.floor(whole / 60);
    return `${minutes}:${String(whole % 60).padStart(2, '0')}`;
  }
  const hours = Math.floor(whole / 3600);
  const minutes = Math.floor((whole % 3600) / 60);
  return `${hours}h ${String(minutes).padStart(2, '0')}m`;
}
