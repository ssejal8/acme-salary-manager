import { provideHttpClient } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { Router } from '@angular/router';
import { StoredSession, TokenResponse } from './auth.models';
import { AuthService } from './auth.service';
import { TokenStorage } from './token-storage';

const LOGIN_URL = '/api/v1/auth/login';
const STORAGE_KEY = 'acme.salary.session';

function tokenResponse(overrides: Partial<TokenResponse> = {}): TokenResponse {
  return {
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    tokenType: 'Bearer',
    expiresIn: 3600,
    user: { id: 7, email: 'hr@acme.test', role: 'HR' },
    ...overrides,
  };
}

describe('AuthService', () => {
  let http: HttpTestingController;
  let storage: TokenStorage;
  let navigate: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    localStorage.clear();
    navigate = vi.fn().mockResolvedValue(true);

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(),
        provideHttpClientTesting(),
        { provide: Router, useValue: { navigate, url: '/', parseUrl: () => ({ queryParams: {} }) } },
      ],
    });

    http = TestBed.inject(HttpTestingController);
    storage = TestBed.inject(TokenStorage);
  });

  afterEach(() => {
    http.verify();
    localStorage.clear();
  });

  /**
   * Injected on demand rather than in `beforeEach`, because the service reads storage
   * when it is constructed — a test about reload behaviour has to seed storage first.
   */
  function service(): AuthService {
    return TestBed.inject(AuthService);
  }

  function login(auth: AuthService, response = tokenResponse()): void {
    auth.login({ email: 'hr@acme.test', password: 'Hr@12345' }).subscribe();
    http.expectOne(LOGIN_URL).flush(response);
  }

  it('starts with nobody signed in', () => {
    const auth = service();

    expect(auth.isAuthenticated()).toBe(false);
    expect(auth.currentUser()).toBeNull();
    expect(auth.role()).toBeNull();
    expect(auth.accessToken()).toBeNull();
  });

  it('posts the credentials to the login endpoint', () => {
    const auth = service();
    auth.login({ email: 'hr@acme.test', password: 'Hr@12345' }).subscribe();

    const request = http.expectOne(LOGIN_URL);
    expect(request.request.method).toBe('POST');
    expect(request.request.body).toEqual({ email: 'hr@acme.test', password: 'Hr@12345' });
    request.flush(tokenResponse());
  });

  it('holds the caller and the token once signed in', () => {
    const auth = service();

    login(auth);

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.currentUser()).toEqual({ id: 7, email: 'hr@acme.test', role: 'HR' });
    expect(auth.role()).toBe('HR');
    expect(auth.accessToken()).toBe('access-token');
  });

  it('persists the session so a reload is not a sign-out', () => {
    const auth = service();

    login(auth);

    const persisted = storage.read();
    expect(persisted?.accessToken).toBe('access-token');
    expect(persisted?.refreshToken).toBe('refresh-token');
    expect(persisted?.user.email).toBe('hr@acme.test');
  });

  it('is signed in immediately when storage already holds a session', () => {
    // What a page reload looks like: the service is constructed with storage populated.
    const stored: StoredSession = {
      accessToken: 'stored-token',
      refreshToken: 'stored-refresh',
      user: { id: 1, email: 'admin@acme.test', role: 'ADMIN' },
      expiresAt: Date.now() + 60_000,
    };
    localStorage.setItem(STORAGE_KEY, JSON.stringify(stored));

    const auth = service();

    expect(auth.isAuthenticated()).toBe(true);
    expect(auth.role()).toBe('ADMIN');
    expect(auth.accessToken()).toBe('stored-token');
  });

  it('turns the expiry interval into an absolute instant on this clock', () => {
    // expiresIn is sent rather than an absolute time precisely so a wrong client clock
    // cannot make a fresh token look stale. Both ends of the comparison are local.
    const auth = service();
    const before = Date.now();

    login(auth, tokenResponse({ expiresIn: 60 }));

    const after = Date.now();
    const expiresAt = storage.read()?.expiresAt ?? 0;
    expect(expiresAt).toBeGreaterThanOrEqual(before + 60_000);
    expect(expiresAt).toBeLessThanOrEqual(after + 60_000);
    expect(auth.isAccessTokenExpired()).toBe(false);
  });

  it('reports a token whose lifetime has passed as expired', () => {
    const auth = service();

    login(auth, tokenResponse({ expiresIn: -1 }));

    expect(auth.isAccessTokenExpired()).toBe(true);
  });

  it('treats no session at all as expired', () => {
    expect(service().isAccessTokenExpired()).toBe(true);
  });

  describe('roles', () => {
    it('answers hasAnyRole against the signed-in role', () => {
      const auth = service();

      login(auth);

      expect(auth.hasAnyRole('HR')).toBe(true);
      expect(auth.hasAnyRole('ADMIN', 'HR')).toBe(true);
      expect(auth.hasAnyRole('ADMIN')).toBe(false);
      expect(auth.hasAnyRole('EMPLOYEE')).toBe(false);
    });

    it('answers no role at all for a visitor', () => {
      expect(service().hasAnyRole('ADMIN', 'HR', 'EMPLOYEE')).toBe(false);
    });

    it('does not offer employee management to an EMPLOYEE', () => {
      const auth = service();

      login(
        auth,
        tokenResponse({ user: { id: 9, email: 'asha.menon@acme.test', role: 'EMPLOYEE' } }),
      );

      expect(auth.canManageEmployees()).toBe(false);
    });

    it('offers employee management to ADMIN and HR', () => {
      const auth = service();

      login(auth, tokenResponse({ user: { id: 1, email: 'admin@acme.test', role: 'ADMIN' } }));

      expect(auth.canManageEmployees()).toBe(true);
    });
  });

  describe('signing out', () => {
    it('clears the session in memory and in storage, and makes no request', () => {
      const auth = service();
      login(auth);

      auth.logout();

      expect(auth.isAuthenticated()).toBe(false);
      expect(auth.currentUser()).toBeNull();
      expect(storage.read()).toBeNull();
      // There is no logout endpoint: a stateless token cannot be revoked (ADR-004), so a
      // server call would report a success it could not deliver. http.verify() in
      // afterEach is what proves none was made.
    });

    it('returns to the login screen', () => {
      const auth = service();
      login(auth);

      auth.logout();

      expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: {} });
    });

    it('says so when the session ended rather than was ended', () => {
      const auth = service();
      login(auth);

      auth.logout({ expired: true });

      expect(navigate).toHaveBeenCalledWith(['/login'], { queryParams: { expired: 'true' } });
    });
  });

  it('leaves a failed login with nobody signed in', () => {
    const auth = service();

    auth.login({ email: 'hr@acme.test', password: 'wrong' }).subscribe({ error: () => undefined });
    http
      .expectOne(LOGIN_URL)
      .flush(
        { status: 401, message: 'Invalid email or password' },
        { status: 401, statusText: 'Unauthorized' },
      );

    expect(auth.isAuthenticated()).toBe(false);
    expect(storage.read()).toBeNull();
  });
});
