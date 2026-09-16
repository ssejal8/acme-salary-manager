import { HttpErrorResponse, HttpInterceptorFn } from '@angular/common/http';
import { catchError, throwError } from 'rxjs';
import { ApiErrorBody, ApiFailure } from './api-error';

/**
 * Turns every failure into an {@link ApiFailure} with a message fit to show a user.
 *
 * Components then handle one error type with one message field, instead of each deciding
 * what a 409 means or whether `error.error` holds an envelope, a string, or a
 * `ProgressEvent` from a dropped connection.
 *
 * The server's message is preferred wherever there is one: it is the specific and
 * accurate sentence — "Deductions may not exceed gross pay" — and the API is written not
 * to leak internals into it (NFR-2.6). The fallbacks below exist for the cases where
 * there is no envelope to read.
 */
export const errorInterceptor: HttpInterceptorFn = (request, next) =>
  next(request).pipe(
    catchError((error: unknown) => {
      if (!(error instanceof HttpErrorResponse)) {
        // Not an HTTP failure at all — a bug in a downstream operator, most likely.
        // Rethrowing it unchanged keeps the original stack for debugging.
        return throwError(() => error);
      }
      return throwError(() => toFailure(error));
    }),
  );

function toFailure(error: HttpErrorResponse): ApiFailure {
  const body = asApiErrorBody(error.error);

  if (body) {
    return new ApiFailure(error.status, body.message, body.fieldErrors ?? []);
  }
  return new ApiFailure(error.status, fallbackMessage(error.status));
}

/**
 * Recognises the envelope. A failing request can carry almost anything — a proxy's HTML
 * error page, a `ProgressEvent`, nothing at all — so the shape is checked rather than
 * assumed.
 */
function asApiErrorBody(body: unknown): ApiErrorBody | null {
  if (typeof body !== 'object' || body === null) {
    return null;
  }
  const candidate = body as Partial<ApiErrorBody>;
  const usable = typeof candidate.message === 'string' && candidate.message.length > 0;
  return usable ? (candidate as ApiErrorBody) : null;
}

/**
 * What to say when there was no envelope.
 *
 * Each of these is a case where the request did not reach the application's error handler,
 * so there is no server-authored sentence to show.
 */
function fallbackMessage(status: number): string {
  switch (status) {
    case 0:
      // Status 0 is not a server response: the request never completed. Almost always the
      // API is not running, which during development is the single most common failure.
      return 'Could not reach the server. Check that the API is running.';
    case 401:
      return 'Your session has ended. Please sign in again.';
    case 403:
      return 'You are not permitted to perform this action.';
    case 404:
      return 'That record could not be found.';
    case 504:
    case 502:
    case 503:
      return 'The server is not responding. Please try again shortly.';
    default:
      return status >= 500
        ? 'Something went wrong on the server. Please try again.'
        : 'That request could not be completed.';
  }
}
