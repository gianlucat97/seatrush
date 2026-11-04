import type { QueuePlace, QueueUpdate } from '../api/types';

/**
 * What a waiting client is, as one value (README section 12.3).
 *
 * The API answers a place whose fields are present or absent according to the state it is in, and
 * reading it field by field is where a client goes wrong: the interesting cases are the ones where
 * a field's absence is the whole message. This module turns that answer into a tagged union, so
 * every state a page can be in is a name a component switches on and nothing is decided by a
 * null check in the middle of some JSX.
 *
 * There are six states and one of them exists only because of an absence. `ADMITTED` with no
 * admission token means the buyer was let through and their time ran out: the token is minted from
 * a live slot on every read, so no token means no slot. That is a different thing from being
 * queued, and a different thing from never having joined, because the answer to it is to join
 * again rather than to wait or to retry.
 */
export type WaitingPhase =
  /** Not in this waiting room. The only state a user can choose to be in. */
  | { kind: 'away' }
  /** In the lobby of a sale that has not opened. No position: the shuffle has not happened. */
  | { kind: 'lobby'; onSaleAt: string | null }
  /** In the queue, with a place in it. */
  | { kind: 'queued'; position: number; estimatedWaitSeconds: number | null }
  /** Through the door, with the admission to go shopping with and the moment it lapses. */
  | { kind: 'admitted'; admissionToken: string; admissionExpiresAt: string }
  /** Let in, and out again: the slot expired before the buyer used it. */
  | { kind: 'lapsed' }
  /** There is nothing left to buy, so the queue is over for everybody who was in it. */
  | { kind: 'soldOut' };

/** The three names the waiting room stream sends, which a client branches on before parsing. */
export type QueueEventName = 'POSITION' | 'ADMITTED' | 'SOLD_OUT';

export function isQueueEventName(name: string): name is QueueEventName {
  return name === 'POSITION' || name === 'ADMITTED' || name === 'SOLD_OUT';
}

/**
 * Reads a place into the state it describes.
 *
 * A queued user with no position is read as the lobby rather than as a queue of unknown length:
 * the two always travel together, so one without the other is an answer this client cannot show,
 * and the lobby is the honest fallback because it promises the user nothing.
 */
export function phaseOf(place: QueuePlace): WaitingPhase {
  switch (place.state) {
    case 'LOBBY':
      return { kind: 'lobby', onSaleAt: place.onSaleAt ?? null };
    case 'QUEUED':
      return place.position === undefined
        ? { kind: 'lobby', onSaleAt: place.onSaleAt ?? null }
        : {
            kind: 'queued',
            position: place.position,
            estimatedWaitSeconds: place.estimatedWaitSeconds ?? null,
          };
    case 'ADMITTED':
      return place.admissionToken === undefined || place.admissionExpiresAt === undefined
        ? { kind: 'lapsed' }
        : {
            kind: 'admitted',
            admissionToken: place.admissionToken,
            admissionExpiresAt: place.admissionExpiresAt,
          };
  }
}

/**
 * Applies one stream message to the state in hand.
 *
 * The name decides what the message is, so a payload missing the field its name promises is
 * ignored rather than guessed at: the client keeps the state it has and the next read of
 * `queue/me` repairs it. The alternative, showing a position of `undefined` or an admission with no
 * token, would be a page that looks broken because a message was.
 */
export function applyUpdate(
  phase: WaitingPhase,
  name: QueueEventName,
  update: QueueUpdate,
): WaitingPhase {
  switch (name) {
    case 'POSITION':
      return update.position === undefined
        ? phase
        : {
            kind: 'queued',
            position: update.position,
            estimatedWaitSeconds: update.estimatedWaitSeconds ?? null,
          };
    case 'ADMITTED':
      return update.admissionToken === undefined || update.admissionExpiresAt === undefined
        ? phase
        : {
            kind: 'admitted',
            admissionToken: update.admissionToken,
            admissionExpiresAt: update.admissionExpiresAt,
          };
    case 'SOLD_OUT':
      return { kind: 'soldOut' };
  }
}

/**
 * Whether there is anything left for the stream to say.
 *
 * Both endings are final for the connection and not for the page: an admitted buyer still has an
 * admission that runs out, and a sold out sale still has a seat map worth looking at. What ends is
 * the waiting, which is what that stream was for.
 */
export function endsTheWait(phase: WaitingPhase): boolean {
  return phase.kind === 'admitted' || phase.kind === 'soldOut';
}

/**
 * The same state once the clock has moved past an admission.
 *
 * Applied by the page on every tick rather than waited for from the server, because nothing will
 * arrive: the stream ended when the buyer was admitted, and the slot expiring is something only the
 * clock knows. A buyer whose time is up is told so by the page before the gateway tells them so by
 * refusing a hold.
 */
export function atTime(phase: WaitingPhase, now: number): WaitingPhase {
  if (phase.kind !== 'admitted') {
    return phase;
  }
  const expiresAt = Date.parse(phase.admissionExpiresAt);
  if (Number.isNaN(expiresAt)) {
    // An expiry this client cannot read. Keeping the admission is the safer of the two mistakes:
    // the gateway validates the token itself, so at worst one hold is refused.
    return phase;
  }
  return expiresAt <= now ? { kind: 'lapsed' } : phase;
}

/**
 * How long until a sale opens, or nothing when it has.
 *
 * Nothing rather than zero for a sale whose time has passed: the lobby is drained by a scheduler
 * rather than by the clock striking, so a countdown that reached zero and stopped would be telling
 * the user the shuffle has happened when it may be a moment away.
 */
export function secondsUntil(onSaleAt: string | null, now: number): number | null {
  if (onSaleAt === null) {
    return null;
  }
  const parsed = Date.parse(onSaleAt);
  if (Number.isNaN(parsed) || parsed <= now) {
    return null;
  }
  return Math.ceil((parsed - now) / 1000);
}
