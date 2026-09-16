import { provideHttpClient, withFetch, withInterceptors } from '@angular/common/http';
import { ApplicationConfig, provideBrowserGlobalErrorListeners } from '@angular/core';
import { provideRouter, withComponentInputBinding, withInMemoryScrolling } from '@angular/router';
import { routes } from './app.routes';
import { authInterceptor } from './core/http/auth.interceptor';
import { errorInterceptor } from './core/http/error.interceptor';
import { loadingInterceptor } from './core/http/loading.interceptor';

export const appConfig: ApplicationConfig = {
  providers: [
    provideBrowserGlobalErrorListeners(),
    provideRouter(
      routes,
      // Route params arrive as component inputs, so a component does not need to inject
      // ActivatedRoute just to read an id.
      withComponentInputBinding(),
      // Navigating to a new page should start at the top; returning via Back should not.
      withInMemoryScrolling({ scrollPositionRestoration: 'enabled', anchorScrolling: 'enabled' }),
    ),
    provideHttpClient(
      withFetch(),
      /**
       * This order is load-bearing, and not the obvious one.
       *
       * A request passes through the list top to bottom, so the first entry is outermost.
       * A failure propagates back the other way: the *innermost* interceptor's
       * `catchError` runs first.
       *
       * That is why `authInterceptor` is last rather than beside the other auth code.
       * It needs to inspect a raw `HttpErrorResponse` to recognise a 401 and end the
       * session, and `errorInterceptor` replaces that with an `ApiFailure` as the error
       * travels outward. With the two the other way round, the `instanceof
       * HttpErrorResponse` check in the auth interceptor would never match and an expired
       * token would leave the user staring at a shell that failed to load.
       *
       * Read outward from the backend, then: auth sees the real 401 and clears the
       * session, error normalises whatever remains into the one type components handle,
       * and loading — outermost — finishes its count last, so the progress bar covers
       * the whole exchange.
       */
      withInterceptors([loadingInterceptor, errorInterceptor, authInterceptor]),
    ),
  ],
};
