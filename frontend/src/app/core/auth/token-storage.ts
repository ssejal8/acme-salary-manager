import { Injectable } from '@angular/core';
import { StoredSession } from './auth.models';

/**
 * Persists the session across page loads.
 *
 * `localStorage`, and it is worth being honest about the tradeoff rather than
 * implying there is none: a token in `localStorage` is readable by any script that runs on
 * this origin, so it is exposed by an XSS bug in a way an `HttpOnly` cookie would not be.
 * The alternative was not chosen because a cookie the JavaScript cannot read cannot be put
 * in an `Authorization` header either, and the API is a stateless bearer-token API by
 * ADR-004 — a cookie-based session would mean CSRF protection and a server-side session
 * store, which is the design that ADR explicitly rejected.
 *
 * What limits the blast radius is on the server: a 60-minute access token and a
 * `tokenVersion` that a password change invalidates (architecture §8.2).
 *
 * Every read is defensive. The contents are user-writable — a developer console or a
 * stale entry from an older version of this app — so anything unparseable or
 * structurally wrong is treated as "no session" rather than trusted.
 */
@Injectable({ providedIn: 'root' })
export class TokenStorage {
  private static readonly KEY = 'acme.salary.session';

  read(): StoredSession | null {
    const raw = this.backingStore()?.getItem(TokenStorage.KEY);
    if (!raw) {
      return null;
    }
    try {
      return this.validate(JSON.parse(raw));
    } catch {
      // Corrupt entry. Clearing it stops every subsequent load tripping over the same
      // value, which would otherwise look like an intermittent failure to log in.
      this.clear();
      return null;
    }
  }

  write(session: StoredSession): void {
    this.backingStore()?.setItem(TokenStorage.KEY, JSON.stringify(session));
  }

  clear(): void {
    this.backingStore()?.removeItem(TokenStorage.KEY);
  }

  /**
   * Narrows an unknown parsed value to a session, or null.
   *
   * Only the fields this application actually relies on are checked. A deep schema check
   * would be theatre: the value either came from `write` or it is not to be trusted at
   * all, and the server re-verifies the token on every request regardless.
   */
  private validate(value: unknown): StoredSession | null {
    if (typeof value !== 'object' || value === null) {
      return null;
    }
    const candidate = value as Partial<StoredSession>;
    const user = candidate.user;
    const wellFormed =
      typeof candidate.accessToken === 'string' &&
      typeof candidate.refreshToken === 'string' &&
      typeof candidate.expiresAt === 'number' &&
      typeof user === 'object' &&
      user !== null &&
      typeof user.id === 'number' &&
      typeof user.email === 'string' &&
      typeof user.role === 'string';

    return wellFormed ? (candidate as StoredSession) : null;
  }

  /**
   * `localStorage` can throw on access — Safari in private mode historically, and any
   * browser with site data blocked. The app degrades to a session that lasts until reload
   * rather than failing to start.
   */
  private backingStore(): Storage | null {
    try {
      return localStorage;
    } catch {
      return null;
    }
  }
}
