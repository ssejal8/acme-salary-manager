import { ComponentFixture, TestBed } from '@angular/core/testing';
import { DefaultUrlSerializer, Router } from '@angular/router';
import { Observable, of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { ApiFailure } from '../../../core/http/api-error';
import { LoginRequest, TokenResponse } from '../../../core/auth/auth.models';
import { Login } from './login';

const serializer = new DefaultUrlSerializer();

function tokenResponse(): TokenResponse {
  return {
    accessToken: 'access-token',
    refreshToken: 'refresh-token',
    tokenType: 'Bearer',
    expiresIn: 3600,
    user: { id: 7, email: 'hr@acme.test', role: 'HR' },
  };
}

describe('Login', () => {
  let fixture: ComponentFixture<Login>;
  let component: Login;
  let navigateByUrl: ReturnType<typeof vi.fn>;
  let loginResult: () => Observable<TokenResponse>;
  let loginCalls: LoginRequest[];
  let currentUrl: string;

  /** Built per test, because the component reads the URL when it is constructed. */
  function createComponent(url = '/login'): void {
    currentUrl = url;
    fixture = TestBed.createComponent(Login);
    component = fixture.componentInstance;
    fixture.detectChanges();
  }

  beforeEach(() => {
    navigateByUrl = vi.fn().mockResolvedValue(true);
    loginResult = () => of(tokenResponse());
    loginCalls = [];
    currentUrl = '/login';

    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            login: (credentials: LoginRequest) => {
              loginCalls.push(credentials);
              return loginResult();
            },
          },
        },
        {
          provide: Router,
          useValue: {
            get url() {
              return currentUrl;
            },
            parseUrl: (value: string) => serializer.parse(value),
            navigateByUrl,
          },
        },
      ],
    });
  });

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  function query(selector: string): HTMLElement | null {
    return (fixture.nativeElement as HTMLElement).querySelector(selector);
  }

  it('starts with an empty, untouched form', () => {
    createComponent();

    expect(component.form.getRawValue()).toEqual({ email: '', password: '' });
    expect(component.errorMessage()).toBeNull();
    expect(component.submitting()).toBe(false);
  });

  describe('client-side validation', () => {
    it('does not call the service when the form is empty', () => {
      createComponent();

      component.submit();

      expect(loginCalls).toEqual([]);
    });

    it('reveals every message at once rather than one control at a time', () => {
      createComponent();

      component.submit();
      fixture.detectChanges();

      expect(component.showsError('email')).toBe(true);
      expect(component.showsError('password')).toBe(true);
      expect(text()).toContain('Enter your email address');
      expect(text()).toContain('Enter your password');
    });

    it('rejects an address that is not an email, without a round trip', () => {
      createComponent();
      component.form.setValue({ email: 'not-an-email', password: 'Hr@12345' });

      component.submit();

      expect(loginCalls).toEqual([]);
      expect(component.errorFor('email')).toContain('valid email address');
    });

    it('says nothing about a control the user has not visited', () => {
      createComponent();

      expect(component.showsError('email')).toBe(false);
    });
  });

  describe('a successful sign-in', () => {
    beforeEach(() => {
      createComponent();
      component.form.setValue({ email: 'hr@acme.test', password: 'Hr@12345' });
    });

    it('sends the credentials as entered', () => {
      component.submit();

      expect(loginCalls).toEqual([{ email: 'hr@acme.test', password: 'Hr@12345' }]);
    });

    it('goes to the employee list by default', () => {
      component.submit();

      expect(navigateByUrl).toHaveBeenCalledWith('/employees', { replaceUrl: true });
    });

    it('replaces the history entry, so Back does not return to the login screen', () => {
      component.submit();

      expect(navigateByUrl).toHaveBeenCalledWith(expect.anything(), { replaceUrl: true });
    });
  });

  it('resumes where the visitor was headed', () => {
    // The guard puts the attempted URL here, which is what makes a pasted link work.
    createComponent('/login?redirectTo=%2Femployees%2F1001');
    component.form.setValue({ email: 'hr@acme.test', password: 'Hr@12345' });

    component.submit();

    expect(navigateByUrl).toHaveBeenCalledWith('/employees/1001', { replaceUrl: true });
  });

  describe('a rejected sign-in', () => {
    beforeEach(() => {
      loginResult = () =>
        throwError(() => new ApiFailure(401, 'Invalid email or password'));
      createComponent();
      component.form.setValue({ email: 'hr@acme.test', password: 'wrong' });
    });

    it("shows the server's message", () => {
      component.submit();
      fixture.detectChanges();

      expect(component.errorMessage()).toBe('Invalid email or password');
      expect(text()).toContain('Invalid email or password');
    });

    it('announces the failure rather than only colouring it', () => {
      component.submit();
      fixture.detectChanges();

      expect(query('[role="alert"]')?.textContent).toContain('Invalid email or password');
    });

    it('stays on the form and lets the user try again', () => {
      component.submit();

      expect(navigateByUrl).not.toHaveBeenCalled();
      expect(component.submitting()).toBe(false);
    });

    it('keeps what the user typed', () => {
      // Losing the email on every wrong password is a small cruelty.
      component.submit();

      expect(component.form.controls.email.value).toBe('hr@acme.test');
    });

    it('falls back to a generic message for a failure it cannot read', () => {
      loginResult = () => throwError(() => new Error('socket hang up'));
      createComponent();
      component.form.setValue({ email: 'hr@acme.test', password: 'Hr@12345' });

      component.submit();

      expect(component.errorMessage()).toBe('Sign-in failed. Please try again.');
    });
  });

  describe('arriving because the session ended', () => {
    it('explains why the login screen appeared', () => {
      createComponent('/login?expired=true');
      fixture.detectChanges();

      expect(component.sessionExpired()).toBe(true);
      expect(text()).toContain('Your session ended');
    });

    it('says nothing when the user chose to sign in', () => {
      createComponent('/login');

      expect(component.sessionExpired()).toBe(false);
      expect(text()).not.toContain('Your session ended');
    });

    it('drops the notice once a new attempt is made', () => {
      createComponent('/login?expired=true');

      component.form.setValue({ email: 'hr@acme.test', password: 'Hr@12345' });
      component.submit();

      expect(component.sessionExpired()).toBe(false);
    });
  });

  it('ignores a second submit while one is in flight', () => {
    // Never completes, standing in for a slow network.
    loginResult = () => new Observable<TokenResponse>(() => undefined);
    createComponent();
    component.form.setValue({ email: 'hr@acme.test', password: 'Hr@12345' });

    component.submit();
    component.submit();

    expect(loginCalls).toHaveLength(1);
  });

  it('marks the password field for a password manager, not autofill of a username', () => {
    createComponent();

    expect(query('#password')?.getAttribute('autocomplete')).toBe('current-password');
    expect(query('#email')?.getAttribute('autocomplete')).toBe('username');
  });

  it('describes an invalid control to assistive technology, not only in colour', () => {
    createComponent();

    component.submit();
    fixture.detectChanges();

    expect(query('#email')?.getAttribute('aria-invalid')).toBe('true');
    expect(query('#email')?.getAttribute('aria-describedby')).toBe('email-error');
    expect(query('#email-error')).not.toBeNull();
  });
});
