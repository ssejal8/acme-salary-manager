import { HttpClient, provideHttpClient, withInterceptors } from '@angular/common/http';
import { HttpTestingController, provideHttpClientTesting } from '@angular/common/http/testing';
import { TestBed } from '@angular/core/testing';
import { AuthService } from '../auth/auth.service';
import { authInterceptor } from './auth.interceptor';
import { errorInterceptor } from './error.interceptor';

const EMPLOYEES_URL = '/api/v1/employees';
const LOGIN_URL = '/api/v1/auth/login';
const REFRESH_URL = '/api/v1/auth/refresh';

/**
 * The interceptors are registered in the same order as `app.config.ts`, because the order
 * is what makes the 401 handling work: `errorInterceptor` is outermost, so
 * `authInterceptor` still sees a raw `HttpErrorResponse` rather than an already-converted
 * `ApiFailure`. Testing them in isolation would pass while the application did not.
 */
describe('authInterceptor', () => {
  let http: HttpClient;
  let backend: HttpTestingController;
  let logout: ReturnType<typeof vi.fn>;
  let token: string | null;

  beforeEach(() => {
    token = 'access-token';
    logout = vi.fn();

    TestBed.configureTestingModule({
      providers: [
        provideHttpClient(withInterceptors([errorInterceptor, authInterceptor])),
        provideHttpClientTesting(),
        {
          provide: AuthService,
          useValue: {
            accessToken: () => token,
            logout,
          },
        },
      ],
    });
    http = TestBed.inject(HttpClient);
    backend = TestBed.inject(HttpTestingController);
  });

  afterEach(() => backend.verify());

  it('attaches the bearer token to an application request', () => {
    http.get(EMPLOYEES_URL).subscribe();

    const request = backend.expectOne(EMPLOYEES_URL);
    expect(request.request.headers.get('Authorization')).toBe('Bearer access-token');
    request.flush({});
  });

  it('sends no Authorization header when there is no session', () => {
    token = null;
    http.get(EMPLOYEES_URL).subscribe();

    const request = backend.expectOne(EMPLOYEES_URL);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('does not attach a token when logging in', () => {
    // A user whose session has expired still holds a token. Sending it to the login
    // endpoint is pointless, and on refresh it would be the wrong token entirely.
    http.post(LOGIN_URL, { email: 'hr@acme.test', password: 'Hr@12345' }).subscribe();

    const request = backend.expectOne(LOGIN_URL);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  it('does not attach a token when refreshing', () => {
    http.post(REFRESH_URL, { refreshToken: 'refresh-token' }).subscribe();

    const request = backend.expectOne(REFRESH_URL);
    expect(request.request.headers.has('Authorization')).toBe(false);
    request.flush({});
  });

  describe('when the server rejects the token', () => {
    it('ends the session and says it expired', () => {
      http.get(EMPLOYEES_URL).subscribe({ error: () => undefined });
      backend
        .expectOne(EMPLOYEES_URL)
        .flush({ status: 401, message: 'Authentication is required' }, { status: 401, statusText: 'Unauthorized' });

      expect(logout).toHaveBeenCalledWith({ expired: true });
    });

    it('still propagates the failure, so a screen can react', () => {
      let failed = false;
      http.get(EMPLOYEES_URL).subscribe({ error: () => (failed = true) });
      backend.expectOne(EMPLOYEES_URL).flush(null, { status: 401, statusText: 'Unauthorized' });

      expect(failed).toBe(true);
    });
  });

  describe('401s that are not an expired session', () => {
    it('does not end the session when the login itself is rejected', () => {
      // This is the important one. A wrong password is a 401, and treating it as an
      // expiry would navigate away from the form and lose what the user typed.
      http.post(LOGIN_URL, { email: 'hr@acme.test', password: 'wrong' }).subscribe({
        error: () => undefined,
      });
      backend
        .expectOne(LOGIN_URL)
        .flush({ status: 401, message: 'Invalid email or password' }, { status: 401, statusText: 'Unauthorized' });

      expect(logout).not.toHaveBeenCalled();
    });

    it('does not end a session that never existed', () => {
      token = null;
      http.get(EMPLOYEES_URL).subscribe({ error: () => undefined });
      backend.expectOne(EMPLOYEES_URL).flush(null, { status: 401, statusText: 'Unauthorized' });

      expect(logout).not.toHaveBeenCalled();
    });
  });

  it('leaves a 403 alone: the token is good, the role is not', () => {
    // Signing the user out here would be wrong and confusing — a different password
    // would not help.
    http.get(EMPLOYEES_URL).subscribe({ error: () => undefined });
    backend
      .expectOne(EMPLOYEES_URL)
      .flush({ status: 403, message: 'You are not permitted to perform this action' }, { status: 403, statusText: 'Forbidden' });

    expect(logout).not.toHaveBeenCalled();
  });

  it('leaves other failures alone', () => {
    http.get(EMPLOYEES_URL).subscribe({ error: () => undefined });
    backend.expectOne(EMPLOYEES_URL).flush(null, { status: 500, statusText: 'Server Error' });

    expect(logout).not.toHaveBeenCalled();
  });
});
