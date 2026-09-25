import { Component, computed, inject, signal } from '@angular/core';
import { toSignal } from '@angular/core/rxjs-interop';
import { FormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { map } from 'rxjs';
import { AuthService } from '../../../core/auth/auth.service';
import { ApiFailure } from '../../../core/http/api-error';

/** Mirrors the server's policy, which is length and nothing else. */
const MIN_PASSWORD_LENGTH = 10;

/**
 * Change your own password (FR-1.6).
 *
 * ## Why it signs you out
 *
 * Changing a password moves the user's `tokenVersion`, and the API compares that on every
 * request (ADR-004) — so the token this screen used is dead the moment the request
 * succeeds. Staying on the page would mean every subsequent request answering 401 and the
 * interceptor ending the session anyway, one confusing step later. Signing out
 * deliberately, with an explanation, is the honest version of what has already happened.
 *
 * ## What it does not check
 *
 * Whether the current password is right. That is the server's answer — it holds the hash —
 * and it comes back as a **400** with a field error rather than a 401, precisely so the
 * auth interceptor does not read a mistyped password as an expired session.
 */
@Component({
  selector: 'app-change-password',
  imports: [ReactiveFormsModule],
  templateUrl: './change-password.html',
  styleUrl: './change-password.scss',
})
export class ChangePassword {
  private readonly auth = inject(AuthService);
  private readonly formBuilder = inject(FormBuilder);

  readonly minLength = MIN_PASSWORD_LENGTH;

  readonly form = this.formBuilder.nonNullable.group({
    currentPassword: ['', [Validators.required]],
    newPassword: ['', [Validators.required, Validators.minLength(MIN_PASSWORD_LENGTH)]],
    confirmation: ['', [Validators.required]],
  });

  readonly saving = signal(false);
  readonly failure = signal<ApiFailure | null>(null);
  readonly attempted = signal(false);

  /**
   * The form's values, bridged into the signal graph.
   *
   * A `computed` reading the controls directly would never recompute — a control's value
   * is not a signal — so the confirmation mismatch would be reported against whatever the
   * form held when the screen opened.
   */
  private readonly values = toSignal(
    // Mapped to the raw value rather than taken from the emission: `valueChanges` gives a
    // partial, where every field is optionally undefined, and none of them ever are here.
    this.form.valueChanges.pipe(map(() => this.form.getRawValue())),
    { initialValue: this.form.getRawValue() },
  );

  /**
   * Whether the confirmation matches.
   *
   * Checked here rather than on the server, and it is the one rule that belongs in the
   * browser: the confirmation box is not sent at all. It exists so a typo in a field
   * nobody can read does not lock somebody out of their own account.
   */
  readonly confirmationMismatch = computed(() => {
    const { newPassword, confirmation } = this.values();
    return confirmation.length > 0 && newPassword !== confirmation;
  });

  readonly currentPasswordError = computed(() => {
    const fromServer = this.failure()?.messageFor('currentPassword');
    if (fromServer) {
      return fromServer;
    }
    return this.attempted() && !this.values().currentPassword ? 'This is required' : null;
  });

  readonly newPasswordError = computed(() => {
    const fromServer = this.failure()?.messageFor('newPassword');
    if (fromServer) {
      return fromServer;
    }
    const value = this.values().newPassword;
    if (!this.attempted() && value.length === 0) {
      return null;
    }
    if (value.length < MIN_PASSWORD_LENGTH) {
      return `Use at least ${MIN_PASSWORD_LENGTH} characters`;
    }
    return null;
  });

  /** A refusal that names no field — a session that expired mid-change, for instance. */
  readonly generalError = computed(() => {
    const failure = this.failure();
    return failure && !failure.hasFieldErrors ? failure.message : null;
  });

  submit(): void {
    this.attempted.set(true);
    if (this.form.invalid || this.confirmationMismatch() || this.saving()) {
      return;
    }

    this.saving.set(true);
    this.failure.set(null);

    const { currentPassword, newPassword } = this.form.getRawValue();
    // The confirmation is deliberately not sent: it is a check on the typing, not a fact
    // the server needs.
    this.auth.changePassword({ currentPassword, newPassword }).subscribe({
      next: () => {
        // Signed out with an explanation rather than left holding a token the server has
        // already invalidated.
        this.auth.logout({ passwordChanged: true });
      },
      error: (failure: unknown) => {
        this.saving.set(false);
        this.failure.set(failure instanceof ApiFailure ? failure : null);
      },
    });
  }
}
