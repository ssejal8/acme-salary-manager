import { Component, inject } from '@angular/core';
import { RouterLink } from '@angular/router';
import { AuthService } from '../../core/auth/auth.service';

/**
 * Shown when a signed-in user reaches a route their role cannot use.
 *
 * Separate from the login screen on purpose. The user is authenticated — sending them
 * back to sign in would imply a different password would help, when the answer is that
 * this role does not have access.
 */
@Component({
  selector: 'app-not-authorised',
  imports: [RouterLink],
  template: `
    <div class="notice card">
      <h1 class="notice__title">You do not have access to that page</h1>
      <p class="notice__body">
        Your account is signed in as
        <strong>{{ currentUser()?.email }}</strong> with the
        <strong>{{ currentUser()?.role }}</strong> role, which does not include this area.
        Ask an administrator if you think it should.
      </p>
      <a class="button button--primary" routerLink="/">Go back</a>
    </div>
  `,
  styles: `
    .notice {
      max-width: 34rem;
      margin: 0 auto;
      padding: 2rem;
      text-align: center;
    }

    .notice__title {
      font-size: 1.25rem;
      font-weight: 700;
    }

    .notice__body {
      margin: 0.75rem 0 1.5rem;
      color: var(--colour-text-muted);
    }
  `,
})
export class NotAuthorised {
  private readonly auth = inject(AuthService);
  readonly currentUser = this.auth.currentUser;
}
