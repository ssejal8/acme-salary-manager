/**
 * Dev-server proxy: `/api` to the Spring Boot API on port 8080.
 *
 * A `.mjs` file rather than the JSON it replaces, for one reason. Vite's proxy answers a
 * refused connection with **500**, so with the API simply not running the browser saw a
 * genuine `500` response rather than a network failure — and the SPA reported "Something
 * went wrong on the server" for a server that was not there at all. That is the single
 * most common failure during development, and it was the one message that pointed in the
 * wrong direction.
 *
 * The handler below answers **502** instead, which is what a proxy that cannot reach its
 * upstream should say, and carries the API's own `ApiError` envelope so the SPA renders a
 * message that names the real cause. Only a function can do that, hence the module.
 */

/** Matches `com.acme.salary.common.error.ApiError`, so the client parses it as usual. */
function unreachableUpstream(path, cause) {
  return {
    timestamp: new Date().toISOString(),
    status: 502,
    error: 'Bad Gateway',
    message: `Could not reach the API at http://localhost:8080. Is it running? (${cause})`,
    path,
    fieldErrors: [],
  };
}

export default {
  '/api': {
    target: 'http://localhost:8080',
    secure: false,
    changeOrigin: true,
    configure: (proxy) => {
      proxy.on('error', (error, request, response) => {
        // Nothing useful to do once the response has started; rewriting the head would
        // throw and mask the original failure.
        if (!response || response.writableEnded || response.headersSent) {
          return;
        }
        response.writeHead(502, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify(unreachableUpstream(request?.url ?? '/api', error.code ?? error.message)));
      });
    },
  },
};
