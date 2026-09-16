import { HttpClient } from '@angular/common/http';
import { Injectable, computed, inject, signal } from '@angular/core';
import { Router } from '@angular/router';
import { Observable, tap } from 'rxjs';
import { environment } from '../../../environments/environment';
import { AuthenticatedUser, LoginRequest, Role, StoredSession, TokenResponse } from './auth.models';
import { TokenStorage } from './token-storage';

/**
 * The authenticated user, and the only genuinely global state in this application
 * (ADR-019).
 *
 * Everything else is fetched per route and held in component state. This is the exception
 * because the shell, the guards and the auth interceptor all need the same answer to "who
 * is signed in", and threading it through routes would be worse than one service.
 *
 * State is a signal rather than a `BehaviorSubject`: the shell reads it directly in a
 * template, and the app is zoneless, so a signal is what makes the menu re-render when
 * somebody signs out.
 */
@Injectable({ providedIn: 'root' })
export class AuthService {
  private readonly http = inject(HttpClient);
  private readonly storage = inject(TokenStorage);
  private readonly router = inject(Router);

  /** Seeded from storage so a page reload does not look like a sign-out. */
  private readonly session = signal<StoredSession | null>(this.storage.read());

  readonly currentUser = computed<AuthenticatedUser | null>(() => this.session()?.user ?? null);
  readonly isAuthenticated = computed(() => this.session() !== null);
  readonly role = computed<Role | null>(() => this.session()?.user.role ?? null);

  /** Present for the shell; the API is what actually enforces this (NFR-2.2). */
  readonly canManageEmployees = computed(() => this.hasAnyRole('ADMIN', 'HR'));

  accessToken(): string | null {
    return this.session()?.accessToken ?? null;
  }

  hasAnyRole(...roles: Role[]): boolean {
    const current = this.role();
    return current !== null && roles.includes(current);
  }

  /**
   * Exchanges credentials for a session (FR-1.1).
   *
   * The caller subscribes and handles failure — a wrong password is that screen's
   * business, not this service's, and swallowing the error here would leave the form with
   * nothing to display.
   */
  login(credentials: LoginRequest): Observable<TokenResponse> {
    return this.http
      .post<TokenResponse>(`${environment.apiBaseUrl}/auth/login`, credentials)
      .pipe(tap((response) => this.store(response)));
  }

  /**
   * Discards the session and returns to the login screen.
   *
   * There is no server call, because there is nothing for a server to do: the tokens are
   * stateless and cannot be revoked (ADR-004). A logout endpoint would report success
   * while the token it "revoked" kept working, so the honest implementation is local.
   *
   * @param expired when true, the login screen explains that the session timed out rather
   *     than appearing for no reason
   */
  logout(options: { expired?: boolean } = {}): void {
    this.session.set(null);
    this.storage.clear();
    void this.router.navigate(['/login'], {
      queryParams: options.expired ? { expired: 'true' } : {},
    });
  }

  /**
   * Whether the stored access token has passed its expiry by this browser's clock.
   *
   * Advisory only, and deliberately not used to block a request: a client's clock may be
   * wrong in either direction, and the server is the authority on expiry. It exists so a
   * reload with a plainly stale session goes straight to the login screen instead of
   * flashing a shell that immediately 401s.
   */
  isAccessTokenExpired(): boolean {
    const session = this.session();
    return session === null || session.expiresAt <= Date.now();
  }

  private store(response: TokenResponse): void {
    const session: StoredSession = {
      accessToken: response.accessToken,
      refreshToken: response.refreshToken,
      user: response.user,
      expiresAt: Date.now() + response.expiresIn * 1000,
    };
    this.session.set(session);
    this.storage.write(session);
  }
}
