import { inject } from '@angular/core';
import { CanActivateFn, RedirectFunction, Router, UrlTree } from '@angular/router';
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
 * Sends the user to their own landing screen (see `landingRedirect`) rather than to the
 * login screen, because they are signed in — offering the login form would suggest a
 * different password would help. Not to a no-access page either: an EMPLOYEE who types
 * `/audit` has a home to go to, and a dead end with a "Go back" button is one more click
 * to reach it.
 */
export function roleGuard(...allowed: Role[]): CanActivateFn {
  return (): boolean | UrlTree => {
    const auth = inject(AuthService);
    const router = inject(Router);

    return auth.hasAnyRole(...allowed) ? true : router.createUrlTree(['/']);
  };
}

/**
 * Where a signed-in user lands when they open the application root.
 *
 * Role-aware, because a fixed target was a bug rather than a simplification: every screen
 * used to be ADMIN/HR, so an EMPLOYEE authenticated successfully and was then bounced to
 * the no-access page — a dead end for one of the three roles the system has.
 *
 * ADMIN and HR go to the employee list, which is their working screen. Everyone else goes
 * to their own payslips, which is theirs.
 */
export const landingRedirect: RedirectFunction = () =>
  inject(AuthService).canManageEmployees() ? '/employees' : '/payslips';
