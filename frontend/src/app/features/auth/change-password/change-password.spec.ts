import { ComponentFixture, TestBed } from '@angular/core/testing';
import { Observable, of, throwError } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { ChangePasswordRequest } from '../../../core/auth/auth.models';
import { ApiFailure } from '../../../core/http/api-error';
import { ChangePassword } from './change-password';

describe('ChangePassword', () => {
  let fixture: ComponentFixture<ChangePassword>;
  let component: ChangePassword;

  let requests: ChangePasswordRequest[];
  let logouts: { expired?: boolean; passwordChanged?: boolean }[];
  let changeResult: () => Observable<void>;

  beforeEach(() => {
    requests = [];
    logouts = [];
    changeResult = () => of(undefined);

    TestBed.configureTestingModule({
      providers: [
        {
          provide: AuthService,
          useValue: {
            changePassword: (request: ChangePasswordRequest) => {
              requests.push(request);
              return changeResult();
            },
            logout: (options: { expired?: boolean; passwordChanged?: boolean } = {}) => {
              logouts.push(options);
            },
          },
        },
      ],
    });
  });

  async function createComponent(): Promise<void> {
    fixture = TestBed.createComponent(ChangePassword);
    component = fixture.componentInstance;
    await fixture.whenStable();
    fixture.detectChanges();
  }

  async function settle(): Promise<void> {
    await fixture.whenStable();
    fixture.detectChanges();
  }

  function fill(values: Partial<Record<'currentPassword' | 'newPassword' | 'confirmation', string>>) {
    component.form.patchValue(values);
  }

  function text(): string {
    return (fixture.nativeElement as HTMLElement).textContent ?? '';
  }

  it('sends the current and new password, and nothing else', async () => {
    // The confirmation box is a check on the typing, not a fact the server needs.
    await createComponent();
    fill({
      currentPassword: 'Current@12345',
      newPassword: 'Replacement@123',
      confirmation: 'Replacement@123',
    });

    component.submit();
    await settle();

    expect(requests).toEqual([
      { currentPassword: 'Current@12345', newPassword: 'Replacement@123' },
    ]);
  });

  it('signs the user out afterwards, saying why', async () => {
    // The token this request used is already dead: changing a password moves
    // tokenVersion, so staying put would mean 401s one confusing step later.
    await createComponent();
    fill({
      currentPassword: 'Current@12345',
      newPassword: 'Replacement@123',
      confirmation: 'Replacement@123',
    });

    component.submit();
    await settle();

    expect(logouts).toEqual([{ passwordChanged: true }]);
  });

  it('says up front that it will sign you out', async () => {
    await createComponent();

    expect(text()).toContain('signed out afterwards');
  });

  it('does not submit an empty form', async () => {
    await createComponent();

    component.submit();
    await settle();

    expect(requests).toEqual([]);
    expect(component.currentPasswordError()).toBe('This is required');
  });

  it('refuses a new password shorter than the policy', async () => {
    await createComponent();
    fill({ currentPassword: 'Current@12345', newPassword: 'short', confirmation: 'short' });

    component.submit();
    await settle();

    expect(requests).toEqual([]);
    expect(component.newPasswordError()).toContain('at least 10');
  });

  it('refuses a confirmation that does not match, before asking the server', async () => {
    await createComponent();
    fill({
      currentPassword: 'Current@12345',
      newPassword: 'Replacement@123',
      confirmation: 'Replacement@124',
    });
    await settle();

    expect(component.confirmationMismatch()).toBe(true);

    component.submit();
    await settle();

    expect(requests).toEqual([]);
    expect(text()).toContain('does not match');
  });

  it('reports a mismatch as it is typed, not as the form was first rendered', async () => {
    // A computed over the control's value would never recompute — the bug this project
    // has hit twice before.
    await createComponent();
    fill({ newPassword: 'Replacement@123', confirmation: 'Replacement@123' });
    await settle();
    expect(component.confirmationMismatch()).toBe(false);

    fill({ confirmation: 'Replacement@999' });
    await settle();

    expect(component.confirmationMismatch()).toBe(true);
  });

  it("shows the server's message against the current password", async () => {
    // A wrong current password is a 400 with a field error, not a 401 — otherwise the
    // auth interceptor would end the session over a typo.
    changeResult = () =>
      throwError(
        () =>
          new ApiFailure(400, 'Validation failed', [
            { field: 'currentPassword', message: 'is not correct' },
          ]),
      );
    await createComponent();
    fill({
      currentPassword: 'wrong',
      newPassword: 'Replacement@123',
      confirmation: 'Replacement@123',
    });

    component.submit();
    await settle();

    expect(component.currentPasswordError()).toBe('is not correct');
    expect(logouts).toEqual([]);
  });

  it('keeps the user on the screen when the change was refused', async () => {
    changeResult = () => throwError(() => new ApiFailure(0, 'Could not reach the API'));
    await createComponent();
    fill({
      currentPassword: 'Current@12345',
      newPassword: 'Replacement@123',
      confirmation: 'Replacement@123',
    });

    component.submit();
    await settle();

    expect(logouts).toEqual([]);
    expect(component.generalError()).toBe('Could not reach the API');
    expect(component.saving()).toBe(false);
  });

  it('ignores a second submit while one is in flight', async () => {
    changeResult = () => new Observable<void>(() => undefined);
    await createComponent();
    fill({
      currentPassword: 'Current@12345',
      newPassword: 'Replacement@123',
      confirmation: 'Replacement@123',
    });

    component.submit();
    component.submit();

    expect(requests).toHaveLength(1);
  });
});
