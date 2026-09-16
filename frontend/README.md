# Frontend — ACME Salary Management

The Angular single-page application. Setup, credentials and the API contract are in the
[root README](../README.md); this file covers only what is specific to this module.

```bash
npm install
npm start          # http://localhost:4200, proxying /api to localhost:8080
npm test           # Vitest + Angular TestBed
npm run lint       # ESLint over TypeScript and templates
npm run build      # production bundle into dist/
```

The backend must be running for any screen to load data. Without it, requests fail with
"Could not reach the server" — which is the error interceptor doing its job, not a
frontend fault.

## What is built

Login, the application shell with role-aware navigation, the auth and role guards, the
three HTTP interceptors, the employee list (server-side paging, filtering and sorting),
and a read-only employee detail view. Salary structures, the compensation dashboard,
payroll and payslips are not built.

## Things that will look wrong until you know why

- **`.npmrc` sets `legacy-peer-deps=true`.** npm 10.9.x crashes resolving Vitest's peer
  graph. Not a dependency conflict in this project; delete the file on npm 11.
- **Money is formatted from strings, never parsed.** `shared/money.ts` groups thousands
  by walking the characters, because an IEEE-754 double cannot hold most decimal amounts
  exactly. `Intl.NumberFormat` would be shorter and would break the rule in
  [architecture §6.3](../docs/architecture.md#63-money-and-locale-on-the-client).
- **Dates are formatted from strings too, and `DatePipe` is not used for them.**
  `new Date("2022-06-01")` is UTC midnight, so a date pipe renders every joining date a
  day early anywhere behind UTC. See `shared/dates.ts`.
- **Interceptor order in `app.config.ts` is load-bearing.** `authInterceptor` is
  registered last so it still sees a raw `HttpErrorResponse`; errors propagate
  innermost-first. The comment there explains it, and `auth.interceptor.spec.ts` locks it
  in.
- **Guards hide screens; they do not protect data.** The API authorises every request
  independently. A guard exists so an EMPLOYEE gets an explanation instead of a page full
  of 403s.
- **The app is zoneless.** Anything a template reads must be a signal or an input; a
  mutated plain field will not re-render.
