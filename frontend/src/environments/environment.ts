/**
 * Development settings.
 *
 * `apiBaseUrl` is a relative path, not an absolute one, and that is the point: the dev
 * server proxies `/api` to the backend on port 8080 (see `proxy.conf.json`), and the
 * single-artifact deployment serves the bundle from the API itself (ADR-018). In both
 * cases the browser only ever needs a same-origin path, so there is no CORS
 * configuration and no host to get wrong per environment.
 */
export const environment = {
  production: false,
  apiBaseUrl: '/api/v1',
};
