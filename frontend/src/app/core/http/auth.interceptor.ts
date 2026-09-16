import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { AuthService } from '../auth/auth.service';

/** Paths that must not carry a token, even when one is held. */
const UNAUTHENTICATED_PATHS = ['/auth/login', '/auth/refresh'];

/**
 * Attaches the bearer token, and ends the session when the server rejects it.
 *
 * One place where the token is added, which is the reason interceptors were worth having
 * at all (ADR-003): no feature service constructs an `Authorization` header, so none can
 * forget to.
 */
export const authInterceptor: HttpInterceptorFn = (request, next) => {
  const auth = inject(AuthService);
  const token = auth.accessToken();

  // Login must work while a stale token is still in storage — sending it would be
  // pointless at best, and on refresh the server would read the wrong token entirely.
  const isAuthEndpoint = UNAUTHENTICATED_PATHS.some((path) => request.url.includes(path));

  const authorised =
    token && !isAuthEndpoint
      ? request.clone({ setHeaders: { Authorization: `Bearer ${token}` } })
      : request;

  return next(authorised).pipe(
    catchError((error: unknown) => {
      // A 401 on a request that carried a token means the token is no longer good:
      // expired, or invalidated by a password change or a deactivation server-side. Only
      // the server can know that, so this is the signal to give up the session.
      //
      // Scoped to requests that actually carried one. A 401 from the login endpoint is a
      // wrong password, and treating it as an expiry would redirect the user away from
      // the form and lose what they typed.
      if (error instanceof HttpErrorResponse && error.status === 401 && !isAuthEndpoint && token) {
        auth.logout({ expired: true });
      }
      return throwError(() => error);
    }),
  );
};
