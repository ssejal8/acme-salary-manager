import { TestBed } from '@angular/core/testing';
import { StoredSession } from './auth.models';
import { TokenStorage } from './token-storage';

const STORAGE_KEY = 'acme.salary.session';

function session(overrides: Partial<StoredSession> = {}): StoredSession {
  return {
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    user: { id: 7, email: 'hr@acme.test', role: 'HR' },
    expiresAt: Date.now() + 60_000,
    ...overrides,
  };
}

/** A session with one required field missing, for the structural checks below. */
function without(field: keyof StoredSession): Partial<StoredSession> {
  const incomplete: Partial<StoredSession> = session();
  delete incomplete[field];
  return incomplete;
}

/**
 * The contents of `localStorage` are user-writable — a developer console, a browser
 * extension, or an entry left by an older version of this app. So every read is treated as
 * untrusted input, and these tests are mostly about the malformed cases.
 */
describe('TokenStorage', () => {
  let storage: TokenStorage;

  beforeEach(() => {
    localStorage.clear();
    TestBed.configureTestingModule({});
    storage = TestBed.inject(TokenStorage);
  });

  afterEach(() => localStorage.clear());

  it('round-trips a session', () => {
    const written = session();

    storage.write(written);

    expect(storage.read()).toEqual(written);
  });

  it('reads nothing when nothing was written', () => {
    expect(storage.read()).toBeNull();
  });

  it('forgets a session on clear', () => {
    storage.write(session());

    storage.clear();

    expect(storage.read()).toBeNull();
  });

  describe('untrusted contents', () => {
    it('treats unparseable JSON as no session', () => {
      localStorage.setItem(STORAGE_KEY, 'not json at all');

      expect(storage.read()).toBeNull();
    });

    it('removes an unparseable entry, so it does not fail every load', () => {
      // Left in place, the same bad value would break each page load in turn and look
      // like an intermittent inability to sign in.
      localStorage.setItem(STORAGE_KEY, '{ broken');

      storage.read();

      expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    });

    it('rejects valid JSON that is not a session', () => {
      localStorage.setItem(STORAGE_KEY, '"just a string"');
      expect(storage.read()).toBeNull();

      localStorage.setItem(STORAGE_KEY, 'null');
      expect(storage.read()).toBeNull();

      localStorage.setItem(STORAGE_KEY, '[]');
      expect(storage.read()).toBeNull();
    });

    it('rejects a session missing the token', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(without('accessToken')));

      expect(storage.read()).toBeNull();
    });

    it('rejects a session missing the refresh token', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(without('refreshToken')));

      expect(storage.read()).toBeNull();
    });

    it('rejects a session with no user on it', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(without('user')));

      expect(storage.read()).toBeNull();
    });

    it('rejects a session whose expiry is not a number', () => {
      localStorage.setItem(STORAGE_KEY, JSON.stringify(session({ expiresAt: 'soon' as never })));

      expect(storage.read()).toBeNull();
    });

    it('rejects a user with no role', () => {
      localStorage.setItem(
        STORAGE_KEY,
        JSON.stringify({ ...session(), user: { id: 7, email: 'hr@acme.test' } }),
      );

      expect(storage.read()).toBeNull();
    });
  });
});
