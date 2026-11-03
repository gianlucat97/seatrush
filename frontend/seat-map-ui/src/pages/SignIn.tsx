import { useState } from 'react';

/**
 * The demo login of README section 12.1.
 *
 * A name and nothing else, because that endpoint exists in the local and bench profiles only and
 * takes nothing else. The user id is derived from the name, so coming back as `Ada` comes back as
 * the same buyer who owns Ada's holds and Ada's tickets, and a demo can be reloaded without losing
 * what it was in the middle of.
 *
 * Even watching a seat map needs this. The gateway validates a token on every read, so there is no
 * anonymous spectator: a spectator signs in as anybody.
 */

export interface SignInProps {
  onSignIn: (username: string, admin: boolean) => Promise<void>;
  error: string | null;
  busy: boolean;
}

export function SignIn({ onSignIn, error, busy }: SignInProps) {
  const [username, setUsername] = useState('');
  const [admin, setAdmin] = useState(false);

  const submit = (submitted: React.FormEvent) => {
    submitted.preventDefault();
    const name = username.trim();
    if (name === '' || busy) {
      return;
    }
    void onSignIn(name, admin);
  };

  return (
    <main className="panel sign-in">
      <h1>SeatRush</h1>
      <p className="lead">
        Sign in with any name. The id is derived from it, so the same name is the same buyer every
        time.
      </p>
      <form onSubmit={submit}>
        <label htmlFor="username">Name</label>
        <input
          id="username"
          name="username"
          autoComplete="off"
          autoFocus
          maxLength={60}
          value={username}
          onChange={(changed) => setUsername(changed.target.value)}
          placeholder="Ada"
        />
        <label className="checkbox">
          <input
            type="checkbox"
            checked={admin}
            onChange={(changed) => setAdmin(changed.target.checked)}
          />
          Operator, who may create and open a sale
        </label>
        <button type="submit" disabled={busy || username.trim() === ''}>
          {busy ? 'Signing in' : 'Sign in'}
        </button>
      </form>
      {error !== null && (
        <p className="error" role="alert">
          {error}
        </p>
      )}
    </main>
  );
}
