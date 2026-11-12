import { useCallback, useMemo, useRef, useState } from 'react';
import { ApiClient, ApiError } from './api/client';
import { clearSession, loadSession, saveSession, sessionOf, type Session } from './api/session';
import { EventList } from './pages/EventList';
import { EventPage } from './pages/EventPage';
import { SignIn } from './pages/SignIn';
import { useRoute } from './routing';

/**
 * The demo client of README section 6.7: sign in, pick an event, watch its seat map move.
 *
 * Two things live here because they are true of every view. The session, which the whole page is
 * built on and which the API client reads its token from, and the handling of a 401: a token that
 * has expired means signing in again, and the one place that can decide that is the one place that
 * holds the session.
 *
 * The client is built once and reads the token through a function rather than being rebuilt when
 * the session changes. A new client on every render would be a new identity for every effect that
 * depends on one, and the live seat map would reload its snapshot each time the page re-rendered.
 */
export function App() {
  const [session, setSession] = useState<Session | null>(() => loadSession());
  const [error, setError] = useState<string | null>(null);
  const [signingIn, setSigningIn] = useState(false);
  const route = useRoute();

  /** What the client reads the bearer token from, kept current without rebuilding the client. */
  const token = useRef<string | null>(session?.accessToken ?? null);
  token.current = session?.accessToken ?? null;

  const client = useMemo(() => new ApiClient(() => token.current), []);

  const signOut = useCallback(() => {
    clearSession();
    setSession(null);
  }, []);

  /**
   * One place for everything a call can refuse with.
   *
   * A 401 is the only status that changes what the page is: the session is gone, so the page is the
   * sign in form again. Everything else is a message next to whatever the user was doing.
   */
  const onError = useCallback((failure: unknown) => {
    if (failure instanceof ApiError && failure.isUnauthenticated) {
      clearSession();
      setSession(null);
      setError('The session has expired. Sign in again.');
      return;
    }
    setError(failure instanceof Error ? failure.message : 'Something went wrong.');
  }, []);

  const signIn = useCallback(
    async (username: string, admin: boolean) => {
      setSigningIn(true);
      setError(null);
      try {
        const signed = sessionOf(await client.signIn(username, admin));
        saveSession(signed);
        setSession(signed);
      } catch (failure) {
        setError(
          failure instanceof ApiError
            ? `${failure.message} The demo login exists in the local and bench profiles only.`
            : 'The gateway could not be reached.',
        );
      } finally {
        setSigningIn(false);
      }
    },
    [client],
  );

  if (session === null) {
    return <SignIn onSignIn={signIn} error={error} busy={signingIn} />;
  }

  return (
    <div className="app">
      <header className="app-header">
        <a className="brand" href="#/">
          SeatRush
        </a>
        <div className="who">
          <span>{session.username}</span>
          <button type="button" className="link" onClick={signOut}>
            Sign out
          </button>
        </div>
      </header>

      {error !== null && (
        <p className="error" role="alert">
          {error}
          <button type="button" className="link" onClick={() => setError(null)}>
            Dismiss
          </button>
        </p>
      )}

      {route.kind === 'events' ? (
        <main className="panel">
          <h1>Events</h1>
          <EventList client={client} onError={onError} />
        </main>
      ) : (
        <EventPage
          client={client}
          eventId={route.eventId}
          spectator={route.spectator}
          onError={onError}
        />
      )}
    </div>
  );
}
