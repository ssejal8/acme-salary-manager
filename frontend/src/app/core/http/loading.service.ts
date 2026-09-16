import { Injectable, computed, signal } from '@angular/core';

/**
 * How many requests are outstanding, so the shell can show a progress indicator.
 *
 * A count rather than a boolean: two overlapping requests are normal on these screens —
 * the employee list and its filter dropdowns load together — and with a boolean the
 * first response to arrive would hide the indicator while the second was still in flight.
 */
@Injectable({ providedIn: 'root' })
export class LoadingService {
  private readonly inFlight = signal(0);

  readonly isLoading = computed(() => this.inFlight() > 0);

  started(): void {
    this.inFlight.update((count) => count + 1);
  }

  finished(): void {
    // Floored at zero so a double-finish cannot leave the count negative, which would
    // then swallow the next genuine request's indicator.
    this.inFlight.update((count) => Math.max(0, count - 1));
  }
}
