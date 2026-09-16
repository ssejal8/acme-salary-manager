import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { ApiFailure } from './api-error';
import { errorInterceptor } from './error.interceptor';

const URL = '/api/v1/employees';

/**
 * Every failure a component sees should be an {@link ApiFailure} with something worth
 * displaying in `message` — including the failures that never reached the application's
 * error handler and so carry no envelope.
 */
describe('errorInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;

  beforeEach(() => {
    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor])),
        provideHttpClientTesting(),
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  /**
   * Performs a request, fails it as described, and returns what the caller received.
   *
   * The body is typed loosely because the point of several of these cases is a body that
   * is *not* the envelope — a `ProgressEvent` from a dropped connection, a proxy's HTML.
   */
  function failWith(
    body: Parameters<ReturnType<HttpTestingController['expectOne']>['flush']>[0],
    status: number,
    statusText = 'Error',
  ): ApiFailure {
    let captured: unknown;
    http.get(URL).subscribe({ error: (failure: unknown) => (captured = failure) });
    backend.expectOne(URL).flush(body, { status, statusText });

    expect(captured).toBeInstanceOf(ApiFailure);
    return captured as ApiFailure;
  }

  it('lets a successful response through untouched', () => {
    let received: unknown;
    http.get(URL).subscribe((value) => (received = value));
    backend.expectOne(URL).flush({ content: [] });

    expect(received).toEqual({ content: [] });
  });

  it("prefers the server's own message, which is the specific one", () => {
    const failure = failWith(
      {
        timestamp: '2026-09-16T09:00:00Z',
        status: 400,
        error: 'Bad Request',
        message: 'Deductions may not exceed gross pay',
        path: URL,
      },
      400,
    );

    expect(failure.status).toBe(400);
    expect(failure.message).toBe('Deductions may not exceed gross pay');
  });

  it('carries field errors through, so a form can attach them to controls', () => {
    const failure = failWith(
      {
        status: 400,
        message: 'Validation failed',
        path: URL,
        fieldErrors: [
          { field: 'workEmail', message: 'must be a valid email' },
          { field: 'effectiveFrom', message: 'must not precede the date of joining' },
        ],
      },
      400,
    );

    expect(failure.hasFieldErrors).toBe(true);
    expect(failure.fieldErrors).toHaveLength(2);
    expect(failure.messageFor('workEmail')).toBe('must be a valid email');
    expect(failure.messageFor('effectiveFrom')).toBe('must not precede the date of joining');
  });

  it('reports no field errors when the envelope omits them', () => {
    // Jackson is configured for non_null inclusion, so the key is absent rather than [].
    const failure = failWith({ status: 404, message: 'Employee 42 was not found' }, 404);

    expect(failure.hasFieldErrors).toBe(false);
    expect(failure.fieldErrors).toEqual([]);
    expect(failure.messageFor('anything')).toBeUndefined();
  });

  describe('failures with no envelope to read', () => {
    it('explains an unreachable server, the commonest failure in development', () => {
      const failure = failWith(new ProgressEvent('error'), 0);

      expect(failure.status).toBe(0);
      expect(failure.message).toContain('Could not reach the server');
    });

    it('falls back for a status with no body', () => {
      expect(failWith(null, 403).message).toBe('You are not permitted to perform this action.');
      expect(failWith(null, 404).message).toBe('That record could not be found.');
      expect(failWith(null, 401).message).toContain('session has ended');
    });

    it('does not show a proxy error page as a message', () => {
      // A gateway ahead of the API answers with HTML, not the envelope.
      const failure = failWith('<html><body>502 Bad Gateway</body></html>', 502);

      expect(failure.message).toBe('The server is not responding. Please try again shortly.');
    });

    it('distinguishes a server fault from a client one', () => {
      expect(failWith(null, 500).message).toContain('went wrong on the server');
      expect(failWith(null, 418).message).toBe('That request could not be completed.');
    });

    it('ignores an envelope-shaped body whose message is empty', () => {
      const failure = failWith({ status: 500, message: '' }, 500);

      expect(failure.message).toContain('went wrong on the server');
    });
  });
});
