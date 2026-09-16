import { Component, inject, signal } from '@angular/core';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Router } from '@angular/router';
import { AuthService } from '../../../core/auth/auth.service';
import { ApiFailure } from '../../../core/http/api-error';

/**
 * The sign-in screen (FR-1.1).
 *
 * Validation here mirrors the server's, and only mirrors it: the server checks the same
 * things and is the one that decides. What the client-side copy buys is telling somebody
 * they have mistyped their address without a round trip.
 */
@Component({
  selector: 'app-login',
  imports: [ReactiveFormsModule],
  templateUrl: './login.html',
  styleUrl: './login.scss',
})
export class Login {
  private readonly auth = inject(AuthService);
  private readonly router = inject(Router);
  private readonly formBuilder = inject(FormBuilder);

  readonly form = this.formBuilder.nonNullable.group({
    email: ['', [Validators.required, Validators.email]],
    password: ['', [Validators.required]],
  });

  readonly submitting = signal(false);
  readonly errorMessage = signal<string | null>(null);

  /**
   * Set when the user arrived because their session ended rather than by choosing to sign
   * in. Read once at construction: it explains how they got here, and should not vanish
   * or reappear as the URL is tidied up.
   */
  readonly sessionExpired = signal(
    this.router.parseUrl(this.router.url).queryParams['expired'] === 'true',
  );

  private readonly redirectTo =
    (this.router.parseUrl(this.router.url).queryParams['redirectTo'] as string | undefined) ??
    '/employees';

  submit(): void {
    // Touching the whole form makes every message visible at once, rather than revealing
    // them one control at a time as the user tabs through.
    this.form.markAllAsTouched();
    if (this.form.invalid || this.submitting()) {
      return;
    }

    this.submitting.set(true);
    this.errorMessage.set(null);
    this.sessionExpired.set(false);

    this.auth.login(this.form.getRawValue()).subscribe({
      next: () => {
        // replaceUrl, so Back does not return to the login screen of a session the user
        // is now signed in to.
        void this.router.navigateByUrl(this.redirectTo, { replaceUrl: true });
      },
      error: (failure: unknown) => {
        this.submitting.set(false);
        this.errorMessage.set(
          failure instanceof ApiFailure
            ? failure.message
            : 'Sign-in failed. Please try again.',
        );
      },
    });
  }

  /** Whether a control should show its message: invalid, and the user has been there. */
  showsError(control: 'email' | 'password'): boolean {
    const field = this.form.controls[control];
    return field.invalid && (field.touched || field.dirty);
  }

  errorFor(control: 'email' | 'password'): string {
    const errors = this.form.controls[control].errors;
    if (!errors) {
      return '';
    }
    if (errors['required']) {
      return control === 'email' ? 'Enter your email address' : 'Enter your password';
    }
    if (errors['email']) {
      return 'Enter a valid email address, for example hr@acme.test';
    }
    return 'Check this value';
  }
}
