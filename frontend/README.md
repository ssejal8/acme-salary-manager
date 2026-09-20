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

Every screen whose API exists: login, the shell with role-aware navigation, the auth and
role guards, the three HTTP interceptors, the employee list (server-side paging, filtering
and sorting, state in the URL), the employee record with its compensation and revision
history, the package assignment form with a live server-side preview, the compensation
dashboard, and the salary component definitions.

Not built because the endpoints do not exist: payroll runs, payslips, employee
create/update/deactivate, employee self-service (`/me`), change password, audit trail.
**An EMPLOYEE has no usable screen** for the same reason.

Not built: the salary structure **assignment** form (the write half — preview, grade-band
override, supersede), the compensation dashboard, payroll and payslips.

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
- **The employee list has no state of its own — the URL is the state.** Controls navigate;
  `employee-query.ts` parses the query string back into criteria. So a control must
  navigate *only*, never also set a local signal, or the screen will briefly disagree with
  the URL. The search box is the one exception, and is a `linkedSignal` over the URL's term
  for that reason.
- **`page` is 1-based in the URL, 0-based in the API,** and defaults are omitted from the
  query string entirely.
- **A percentage goes through the `percentage` pipe, never `money`.** A component's
  `configuredValue` is `"12.00"` meaning twelve per cent; as money it would read `₹12.00`
  for a ₹9,000 deduction. `formatPercentage` trims trailing zeros where `formatAmount` pads
  them, which is safe only because no total is derived from a displayed rate.
- **Every figure on the compensation screens comes from the server.** Nothing is summed in
  the browser — the subtotal rows render the server's `grossMonthly` and `totalDeductions`,
  which is what makes the lines tie exactly. The assignment form previews against
  `POST …/preview` on each edit for the same reason.
- **The assignment form has almost no validation of its own.** The preview endpoint runs
  the same checks as the save, so the form asks and displays. The grade-band override box
  appears because the server returns a field error on `overrideReason` — not because the
  form re-implements the band check.
- **A `computed()` over a `FormControl.value` never recomputes** — a control's value is not
  a signal. Bridge it with `toSignal(control.valueChanges)`. This was a real bug in the
  component form's unit label, caught by a test.
- **One flat hue for every bar on the dashboard.** Shading by value would encode the same
  number twice, since the row already names the category.
- **The app is zoneless.** Anything a template reads must be a signal or an input; a
  mutated plain field will not re-render.
