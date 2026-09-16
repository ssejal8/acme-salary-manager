import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { finalize } from 'rxjs';
import { LoadingService } from './loading.service';

/**
 * Drives the shell's progress indicator.
 *
 * `finalize` rather than a `tap` on success: it runs on error and on unsubscribe too. A
 * cancelled request — which is what a component teardown or a `switchMap` on a search box
 * produces — would otherwise leave the counter permanently raised and the bar stuck on.
 */
export const loadingInterceptor: HttpInterceptorFn = (request, next) => {
  const loading = inject(LoadingService);
  loading.started();
  return next(request).pipe(finalize(() => loading.finished()));
};
