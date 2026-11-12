import { useNow } from '../clock';
import type { EventStatus } from '../api/types';
import { formatDuration, secondsLeft } from '../duration';
import { atTime, secondsUntil, type WaitingPhase } from './phase';

/**
 * The waiting room, as the person waiting in it sees it (README section 6.7).
 *
 * Six states and one panel. Each of them says the one thing a person in it can act on: how long
 * until the doors open, how many people are in front of them, how long their turn has left, or
 * that there is nothing left to buy.
 *
 * The clock is read here rather than taken from the server, because two of those sentences are
 * counted down locally. The sale time and the admission expiry both arrive once, as instants, and
 * nothing will arrive when they pass: an admission that lapses is something only the clock knows,
 * and the page has to be the thing that notices.
 */

export interface WaitingRoomProps {
  phase: WaitingPhase;
  /** The status of the sale, which is what makes a refusal to join readable. */
  eventStatus: EventStatus | null;
  error: string | null;
  busy: boolean;
  onJoin: () => void;
  onLeave: () => void;
}

export function WaitingRoom({
  phase,
  eventStatus,
  error,
  busy,
  onJoin,
  onLeave,
}: WaitingRoomProps) {
  const now = useNow();
  // The phase as of this tick: an admission whose moment has passed is a lapsed one, whatever the
  // server last said, because the server has already said everything it is going to.
  const current = atTime(phase, now);

  return (
    <section className={`waiting waiting-${current.kind}`} aria-label="Waiting room">
      <Body phase={current} eventStatus={eventStatus} now={now} busy={busy} onJoin={onJoin} />
      {(current.kind === 'lobby' || current.kind === 'queued') && (
        <button type="button" className="link" onClick={onLeave} disabled={busy}>
          Leave the queue
        </button>
      )}
      {error !== null && (
        <p className="error-inline" role="alert">
          {error}
        </p>
      )}
    </section>
  );
}

function Body({
  phase,
  eventStatus,
  now,
  busy,
  onJoin,
}: {
  phase: WaitingPhase;
  eventStatus: EventStatus | null;
  now: number;
  busy: boolean;
  onJoin: () => void;
}) {
  switch (phase.kind) {
    case 'away':
      return (
        <>
          <h2>{eventStatus === 'SOLD_OUT' ? 'Sold out' : 'Join the queue'}</h2>
          <p className="lead">{awayText(eventStatus)}</p>
          {eventStatus !== 'SOLD_OUT' && eventStatus !== 'CLOSED' && (
            <button type="button" onClick={onJoin} disabled={busy}>
              {busy ? 'Joining' : 'Join the queue'}
            </button>
          )}
        </>
      );

    case 'lobby': {
      const until = secondsUntil(phase.onSaleAt, now);
      return (
        <>
          <h2>In the lobby</h2>
          <p className="lead">
            {until === null
              ? 'The doors are opening. Your place in the queue is being drawn.'
              : 'Everybody here is let into the queue in a random order when the sale opens, so arriving early buys nothing.'}
          </p>
          {until !== null && (
            <p className="countdown" aria-live="polite">
              <span className="countdown-value">{formatDuration(until)}</span>
              <span className="countdown-label">until the sale opens</span>
            </p>
          )}
        </>
      );
    }

    case 'queued':
      return (
        <>
          <h2>In the queue</h2>
          <p className="countdown" aria-live="polite">
            <span className="countdown-value">{phase.position.toLocaleString()}</span>
            <span className="countdown-label">
              {phase.position === 1 ? 'you are next' : 'people ahead of you'}
            </span>
          </p>
          <p className="lead">
            {phase.estimatedWaitSeconds === null
              ? 'The queue has not started moving yet.'
              : `About ${formatDuration(phase.estimatedWaitSeconds)} at the rate the door is moving.`}
          </p>
        </>
      );

    case 'admitted':
      return (
        <>
          <h2>Your turn</h2>
          <p className="countdown" aria-live="polite">
            <span className="countdown-value">
              {formatDuration(secondsLeft(phase.admissionExpiresAt, now))}
            </span>
            <span className="countdown-label">left to choose and pay</span>
          </p>
          <p className="lead">
            Pick your seats on the map. The seats you hold are yours until then.
          </p>
        </>
      );

    case 'lapsed':
      return (
        <>
          <h2>Your turn is over</h2>
          <p className="lead">
            The time to choose ran out, so the seats went back to the sale. Joining again puts you
            at the back of the queue.
          </p>
          <button type="button" onClick={onJoin} disabled={busy}>
            {busy ? 'Joining' : 'Join again'}
          </button>
        </>
      );

    case 'soldOut':
      return (
        <>
          <h2>Sold out</h2>
          <p className="lead">
            The last seat went while you were waiting, so the queue is over. The map shows how the
            venue filled.
          </p>
        </>
      );
  }
}

/** Why somebody is being offered a queue, or why they are not. */
function awayText(eventStatus: EventStatus | null): string {
  switch (eventStatus) {
    case 'SCHEDULED':
      return 'The sale has not opened. Joining now puts you in the lobby, which is drawn in a random order when it does.';
    case 'ON_SALE':
      return 'The sale is open. The queue decides when you get in, and holding a seat is only possible from inside.';
    case 'SOLD_OUT':
      return 'There is nothing left to buy. The map shows how the venue filled.';
    case 'CLOSED':
      return 'This sale is over.';
    case 'DRAFT':
      return 'This event has not been announced yet.';
    default:
      return 'Join the queue to be let in when it is your turn.';
  }
}
