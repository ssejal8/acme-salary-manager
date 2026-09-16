import { Component, input } from '@angular/core';

/**
 * What a list shows when it has nothing to show.
 *
 * Its own component because the distinction it draws is easy to lose and matters to a
 * user: "there are no employees" and "no employees match your filters" call for different
 * next actions, and a blank table says neither.
 */
@Component({
  selector: 'app-empty-state',
  template: `
    <div class="empty-state" role="status">
      <p class="empty-state__title">{{ title() }}</p>
      @if (hint()) {
        <p class="empty-state__hint">{{ hint() }}</p>
      }
      <ng-content />
    </div>
  `,
  styles: `
    .empty-state {
      padding: 2.5rem 1.5rem;
      text-align: center;
      color: var(--colour-text-muted);
    }

    .empty-state__title {
      margin: 0;
      font-size: 1rem;
      font-weight: 600;
      color: var(--colour-text);
    }

    .empty-state__hint {
      margin: 0.5rem 0 0;
      font-size: 0.875rem;
    }
  `,
})
export class EmptyState {
  readonly title = input.required<string>();
  readonly hint = input<string>('');
}
