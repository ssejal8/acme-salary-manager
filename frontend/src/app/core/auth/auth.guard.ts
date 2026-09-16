import { inject } from '@angular/core';
import { CanActivateFn, Router, UrlTree } from '@angular/router';
import { Role } from './auth.models';
import { AuthService } from './auth.service';

/**
 * Keeps unauthenticated visitors off application routes.
 *
 * A convenience, not a security control. It decides what to render, not what data may be
 * read — the API rejects an unauthenticated request whatever the browser did
 * ([NFR-2.2](../../../../../docs/requirements.md)). Its real job is to send someone to the
 * login screen instead of a shell full of failed requests.
 *
 * The attempted URL is carried as `redirectTo` so signing in resumes where the user was
 * headed, which matters most for a link pasted from a colleague.
 */
export const authGuard: CanActivateFn = (_route, state): boolean | UrlTree => {
  const auth = inject(AuthService);
  const router = inject(Router);

  if (auth.isAuthenticated() && !auth.isAccessTokenExpired()) {
    return true;
  }

  // A session that exists but has expired is a timeout, and saying so is the difference
  // between "you were signed out" and an unexplained login screen.
  const expired = auth.isAuthenticated();
  if (expired) {
    auth.logout({ expired: true });
    return false;
  }

  return router.createUrlTree(['/login'], {
    queryParams: { redirectTo: state.url },
  });
};

/**
 * Keeps a signed-in user off routes their role cannot use.
 *
 * Again presentation only: the matching `@PreAuthorize` on the endpoint is the control
 * (FR-1.4). This spares an EMPLOYEE a screen that would only fill with 403s.
 *
 * Returns the not-authorised page rather than the login screen, because the user is
 * signed in — sending them to log in again would suggest that a different password would
 * help.
 */
export function roleGuard(...allowed: Role[]): CanActivateFn {
  return (): boolean | UrlTree => {
    const auth = inject(AuthService);
    const router = inject(Router);

    return auth.hasAnyRole(...allowed) ? true : router.createUrlTree(['/not-authorised']);
  };
}
