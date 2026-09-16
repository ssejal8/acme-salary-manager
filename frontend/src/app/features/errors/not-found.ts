import { Component } from '@angular/core';
import { RouterLink } from '@angular/router';

/** The catch-all route, so a mistyped URL is a page rather than a blank shell. */
@Component({
  selector: 'app-not-found',
  imports: [RouterLink],
  template: `
    <div class="notice card">
      <h1 class="notice__title">Page not found</h1>
      <p class="notice__body">That address does not match anything in this application.</p>
      <a class="button button--primary" routerLink="/employees">Go to employees</a>
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
export class NotFound {}
