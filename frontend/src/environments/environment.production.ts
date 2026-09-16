/**
 * Production settings, substituted for `environment.ts` by the build (see the
 * `fileReplacements` entry in `angular.json`).
 *
 * The API path is identical to development on purpose. The bundle is served from the
 * Spring Boot jar (ADR-018), so `/api/v1` resolves to the same origin without anything
 * being configured at deploy time.
 */
export const environment = {
  production: true,
  apiBaseUrl: '/api/v1',
};
