/**
 * The API's single error envelope, and the shape this application turns it into.
 *
 * Every failing request returns the same body, so there is exactly one thing to parse —
 * see `com.acme.salary.common.error.ApiError`.
 */

/** One rejected input field, so a message can be attached to the right form control. */
export interface ApiFieldError {
  field: string;
  message: string;
}

/** The envelope as it arrives on the wire. */
export interface ApiErrorBody {
  timestamp: string;
  status: number;
  error: string;
  message: string;
  path: string;
  /** Absent rather than empty when there are none — Jackson omits nulls. */
  fieldErrors?: ApiFieldError[];
}

/**
 * A failed request, normalised.
 *
 * The error interceptor converts every failure into one of these, so a component never
 * has to inspect an `HttpErrorResponse`, guess whether a body is present, or decide what
 * a network-level failure should say. Components read `message` and `fieldErrors`.
 */
export class ApiFailure extends Error {
  constructor(
    /** HTTP status, or 0 when the request never reached the server. */
    readonly status: number,
    message: string,
    readonly fieldErrors: ApiFieldError[] = [],
  ) {
    super(message);
    this.name = 'ApiFailure';
  }

  /** Whether this failure can be explained against specific form controls. */
  get hasFieldErrors(): boolean {
    return this.fieldErrors.length > 0;
  }

  /** The message for one control, or undefined if the failure was not about it. */
  messageFor(field: string): string | undefined {
    return this.fieldErrors.find((candidate) => candidate.field === field)?.message;
  }
}
