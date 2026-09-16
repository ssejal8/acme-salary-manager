/**
 * The authentication contract, mirroring the API's DTOs.
 *
 * Hand-written rather than generated from OpenAPI. The surface is small and stable, and a
 * typed mirror that a reviewer can read beside the Java record is worth more here than a
 * build step.
 */

/** Mirrors `com.acme.salary.security.Role`. One role per user, flat by ADR-015. */
export type Role = 'ADMIN' | 'HR' | 'EMPLOYEE';

/** `POST /auth/login` request body. */
export interface LoginRequest {
  email: string;
  password: string;
}

/**
 * Who the caller is, as the API reports them.
 *
 * The role is here so the shell can render an appropriate menu. That is presentation
 * only: the API checks the role on every request (NFR-2.2), so nothing is gained by a
 * client that tampers with this.
 */
export interface AuthenticatedUser {
  id: number;
  email: string;
  role: Role;
}

/** `POST /auth/login` and `POST /auth/refresh` response body. */
export interface TokenResponse {
  accessToken: string;
  refreshToken: string;
  tokenType: string;
  /** Access token lifetime in seconds. */
  expiresIn: number;
  user: AuthenticatedUser;
}

/**
 * What is persisted between page loads.
 *
 * `expiresAt` is computed on receipt from `expiresIn` rather than being sent by the
 * server, because an absolute instant from the server would be read against a possibly
 * wrong client clock. Derived here, both ends of the comparison use the same clock.
 */
export interface StoredSession {
  accessToken: string;
  refreshToken: string;
  user: AuthenticatedUser;
  /** Epoch milliseconds, by the browser's clock. */
  expiresAt: number;
}
