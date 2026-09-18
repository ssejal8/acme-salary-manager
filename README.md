# ACME Salary Management

A web application for managing employee compensation at ACME: HR maintains employees and
their salary structures, runs a monthly payroll cycle, and publishes payslips that
employees can view and download for themselves.

**Status:** in progress, and now end-to-end for the part that exists. Authentication is
implemented — login issues a JWT, the filter chain accepts it, and the `@PreAuthorize`
rules on every endpoint apply to real callers — so the API is reachable with curl rather
than only from tests. Employee master data, reference data, salary component definitions
and effective-dated salary structures are implemented, along with compensation analytics,
the audit trail and the paging contract.

The Angular frontend covers the **shell and the employee screens**: login, a role-aware
layout, the auth guard and interceptors, the paged/filtered/sorted employee list, and a
read-only employee detail view. Salary-structure and compensation-dashboard screens are
not built yet, and neither are payroll runs or payslips at either end. Employee *write*
endpoints do not exist, so the employee screens are read-only by necessity rather than by
choice.

---

## Contents

- [Features](#features)
- [Architecture](#architecture)
- [Tech stack](#tech-stack)
- [Repository layout](#repository-layout)
- [Prerequisites](#prerequisites)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [API](#api)
- [Frontend](#frontend)
- [Roles and permissions](#roles-and-permissions)
- [Testing](#testing)
- [Project conventions](#project-conventions)
- [Documentation](#documentation)
- [Roadmap](#roadmap)

---

## Features

- **Employee master data** — create, update, search, and deactivate employees against
  departments, designations, and grades. Records are never hard-deleted.
- **Salary structures** — assign earning and deduction components per employee with an
  effective-from date. Revisions supersede rather than overwrite, so the full history
  stays auditable, and gross/net/CTC are previewed before saving.
- **Payroll runs** — compute a month's pay for every eligible employee as a reviewable
  draft, adjust loss-of-pay days, recompute, then finalise. Finalised runs are immutable.
- **Payslips** — per-employee payslips with itemised earnings and deductions, viewable
  in-app and downloadable as PDF.
- **Role-based access** — ADMIN, HR, and EMPLOYEE, enforced server-side on every request.
- **Audit trail** — actor, timestamp, and action recorded for every change to employees,
  structures, and payroll runs.

The authoritative, numbered requirement list is in [docs/requirements.md](docs/requirements.md).

## Architecture

An Angular single-page application talks to a stateless Spring Boot REST API over HTTPS.
All business rules — proration, rounding, eligibility, state transitions — live in the
API's service layer. The browser holds no session; requests carry a JWT.

```
┌────────────────┐    HTTPS / JSON     ┌──────────────────────┐     JDBC    ┌────────────┐
│  Angular SPA   │ ──────────────────► │  Spring Boot REST API│ ──────────► │ PostgreSQL │
│  (browser)     │ ◄────────────────── │  (stateless, JWT)    │ ◄────────── │            │
└────────────────┘                     └──────────────────────┘             └────────────┘
```

Layering in the backend is strictly controller → service → repository. Controllers do no
business logic and JPA entities never cross the HTTP boundary — every response is a DTO.

## Tech stack

| Layer | Choice |
| --- | --- |
| Backend | Java 17, Spring Boot 3.x (Web, Validation, Security, Data JPA) |
| Database | PostgreSQL 15+, schema managed by Flyway migrations |
| Auth | JWT bearer tokens (JJWT, HMAC-SHA256), BCrypt password hashing |
| API docs | springdoc-openapi (Swagger UI) |
| Frontend | Angular 21, TypeScript, RxJS, Angular Router — standalone components, signals, zoneless |
| Build | Maven (backend), npm + Angular CLI (frontend) |
| Tests | JUnit 5 + Mockito + Testcontainers (backend), Vitest + Angular TestBed (frontend) |
| Local infra | Docker + docker-compose |

## Repository layout

```
acme-salary-manager/
├── backend/                         Spring Boot REST API (Maven project)
│   ├── pom.xml
│   ├── .env.example
│   └── src/
│       ├── main/java/com/acme/salary/
│       │   ├── SalaryManagementApplication.java
│       │   ├── config/              security, OpenAPI, Jackson, clock
│       │   ├── common/error/        ApiError envelope + one exception handler
│       │   ├── common/money/        scale, rounding, proration, amount-in-words
│       │   ├── common/audit/        audit trail written inside the business transaction
│       │   ├── common/persistence/  entity base classes, JPA auditing
│       │   ├── common/web/          correlation-id filter, paging contract + sanitiser
│       │   ├── employee/            Employee aggregate, repository, search, controller
│       │   ├── orgdata/             departments, designations, grades + read endpoints
│       │   ├── salarycomponent/     component definitions (earnings, deductions)
│       │   ├── report/              compensation analytics (cost, department, grade)
│       │   ├── salarystructure/     effective-dated packages + the pure calculator
│       │   └── security/            login, JWT filter, user read model, 401/403 responders
│       │       └── jwt/             token issuing and verification (no I/O)
│       ├── main/resources/
│       │   ├── application.yml      + application-{dev,prod}.yml
│       │   ├── db/migration/        Flyway migrations (V1 baseline schema)
│       │   └── db/seed/             dev-profile-only fixtures (reference data, employees)
│       └── test/java/com/acme/salary/
├── frontend/                        Angular single-page application
│   ├── angular.json                 build, serve (with the /api proxy), test, lint
│   ├── proxy.conf.json              dev-server proxy: /api → localhost:8080
│   ├── .npmrc                       legacy-peer-deps, and why — see Prerequisites
│   └── src/
│       ├── environments/            per-environment settings (API base path)
│       ├── styles.scss              design tokens + the shared form/table/button classes
│       └── app/
│           ├── core/
│           │   ├── auth/            AuthService, token storage, auth + role guards
│           │   ├── http/            auth, error and loading interceptors; ApiFailure
│           │   ├── layout/          the shell: header, role-aware nav, progress bar
│           │   └── reference-data/  departments, designations, grades
│           ├── shared/              money and date formatting, paging contract, empty state
│           └── features/
│               ├── auth/login/      sign-in screen
│               ├── employees/       list (filter · sort · page) and detail
│               └── errors/          no-access and not-found pages
├── docs/
│   ├── requirements.md              what the system must do
│   ├── architecture.md              how it is built
│   └── decisions.md                 why, and what each choice cost
├── docker-compose.yml               PostgreSQL for local development
└── README.md
```

## Prerequisites

- **JDK 17 or newer** — the build targets Java 17 (ADR-002) and runs on any later JDK
- **Maven 3.9+** — or just use the bundled `./mvnw` wrapper
- **Node.js 22.12+** — Angular 21's floor. Node 20.19+ also works
- **Docker** and Docker Compose — for PostgreSQL locally
- **PostgreSQL 15+** — only if you prefer running the database outside Docker

One wrinkle on the frontend toolchain, documented because it looks like a broken
dependency tree and is not. npm 10.9.x crashes with `Cannot read properties of null
(reading 'edgesOut')` while resolving Vitest's peer graph, which Angular 21 pulls in as its
test runner. `frontend/.npmrc` sets `legacy-peer-deps=true` to work around it, so
`npm install` just works. Delete that file once you are on npm 11 — that is Node 22.22.3+
or 24.15+, which is also what Angular 22 requires, so the two upgrades go together.

## Getting started

Clone, then start the database:

```bash
git clone <repository-url>
cd acme-salary-manager
docker compose up -d db          # PostgreSQL on localhost:5432
```

Run the backend:

```bash
cd backend
./mvnw spring-boot:run           # API on http://localhost:8080
```

The `dev` profile defaults match the compose database, so no `.env` is needed to start.
Copy `.env.example` to `.env` when you need to point somewhere else — see
[Configuration](#configuration).

Flyway applies migrations on startup. Under the `dev` profile two seed migrations also
load a working dataset; that seed location is excluded from every other profile, so
fixtures can never reach a deployed schema.

| Seed | Contents |
| --- | --- |
| `V900` | Reference data — four departments, six designations, four grades with CTC bands, seven salary components |
| `V901` | Twelve employees with fixed ids `1001`–`1012`, nine compensation packages, one raise history, one leaver, two employees with no package |

The employee seed is **deterministic**: ids, dates and row timestamps are all fixed
literals, so employee `1001` is the same person on every machine and the figures below are
reproducible rather than approximate.

| What it shows | Figure |
| --- | --- |
| Headcount / active / active with a package | 12 / 11 / 9 |
| Total active monthly gross | 950,000.00 |
| Total deductions / net | 58,800.00 / 891,200.00 |
| By department | Engineering 580,000 · Finance 140,000 · Sales 140,000 · HR 90,000 |
| Median / average monthly gross | 90,000.00 / 105,555.56 |

Three details are deliberate rather than accidental, because they make the interesting
paths visible in a demo: employee `E-1001` has a **superseded revision** plus a current one
(a raise, so the history screen has something to show); `E-1010` and `E-1011` have **no
package**, which is what compensation analytics reports as a coverage gap; and `E-1012` is
a **leaver** whose package stays on record but is excluded from current cost.

Every seeded package sits inside its grade's CTC band, so nothing in the dataset would
have been rejected had it gone through the API.

Run the frontend in a second terminal:

```bash
cd frontend
npm install
npm start                        # SPA on http://localhost:4200
```

The dev server proxies `/api` to `http://localhost:8080`, so no CORS configuration is
needed locally, and the SPA only ever requests a same-origin path. That is also why the
single-artifact deployment (ADR-018) needs no configuration change: `/api/v1` resolves to
the API in both cases.

Sign in with one of the dev accounts below. `hr@acme.test` is the one to use — ADMIN works
equally, while `asha.menon@acme.test` is an EMPLOYEE and will land on the no-access page,
because listing everyone is not an employee's endpoint (FR-1.5).

**Default dev credentials** (seeded by the `dev` profile only — never enabled in any
deployed environment):

| Role | Email | Password |
| --- | --- | --- |
| ADMIN | `admin@acme.test` | `Admin@123` |
| HR | `hr@acme.test` | `Hr@12345` |
| EMPLOYEE | `asha.menon@acme.test` | `Employee@123` — linked to employee `E-1001` |

### Production build

```bash
cd backend  && ./mvnw clean package          # → target/*.jar
cd frontend && npm run build                 # → dist/
```

## Configuration

The backend reads configuration from the environment; nothing secret is committed. See
`backend/.env.example` for the full list.

| Variable | Purpose | Local default |
| --- | --- | --- |
| `SPRING_PROFILES_ACTIVE` | Active profile | `dev` |
| `DB_URL` | JDBC URL | `jdbc:postgresql://localhost:5432/salary_mgmt` |
| `DB_USERNAME` | Database user | `salary_app` |
| `DB_PASSWORD` | Database password | — (required) |
| `JWT_SECRET` | HMAC signing key, 256-bit minimum | — (required) |
| `JWT_EXPIRY_MINUTES` | Access token lifetime | `60` |
| `SMTP_HOST` / `SMTP_PORT` / `SMTP_USERNAME` / `SMTP_PASSWORD` | Payslip email; optional | unset |

Frontend settings live in `frontend/src/environments/`.

## API

Base path is `/api/v1`. Once the backend is running:

- **Swagger UI** — http://localhost:8080/swagger-ui.html
- **OpenAPI JSON** — http://localhost:8080/v3/api-docs
- **Health** — http://localhost:8080/actuator/health

Authenticate, then send the token on every subsequent call:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"email":"hr@acme.test","password":"Hr@12345"}' | jq -r .accessToken)

curl -s 'http://localhost:8080/api/v1/employees?page=0&size=20' \
  -H "Authorization: Bearer $TOKEN"
```

### Authentication

`POST /auth/login` returns an access token, a refresh token, and who the caller is — the
role travels with the tokens so the SPA can render its menu without a second round trip.

| Endpoint | Roles | Purpose |
| --- | --- | --- |
| `POST /api/v1/auth/login` | public | Exchange credentials for tokens |
| `POST /api/v1/auth/refresh` | public | Exchange a refresh token for a new access token |

Four things about it are deliberate:

- **Every failure is the same 401.** A wrong password, an unknown address and a disabled
  account all answer `Invalid email or password`. The distinction would tell an
  unauthenticated caller which addresses have accounts. The unknown-address path also
  still runs a BCrypt comparison, against a hash of a random string generated at startup,
  so it is not measurably faster than a wrong password either — the same leak by a
  different channel.
- **The refresh token is not rotated.** `/auth/refresh` returns the token you presented,
  unchanged. Re-issuing it each time would slide its 7-day expiry forward without limit,
  and a session that can never end is not a 7-day session (FR-1.7).
- **A token is checked against the database on every request.** One indexed lookup, which
  buys the two things a stateless token cannot tell you: whether the account is still
  enabled, and whether its `tokenVersion` still matches. Without it a deactivated user
  would keep working for up to an hour and a password change would log nobody out — and
  those are the mitigations ADR-004 relies on.
- **There is no logout endpoint.** Tokens are stateless and cannot be revoked, so a server
  logout would report a success it could not deliver. The client discards them instead.

### Reference data

`GET /departments`, `/designations` and `/grades` (ADMIN, HR) return the vocabulary an
employee record is expressed in. Unpaged: these are three closed, small lists whose only
consumer needs all of each or none of it, and they do not grow with headcount.

```bash
curl -s http://localhost:8080/api/v1/grades -H "Authorization: Bearer $TOKEN"
# → [{"id":1,"name":"G1","minCtc":"400000.00","maxCtc":"800000.00"}, ...]
```

A grade's CTC bounds travel with it, because they are what makes a grade mean anything to
a client. **An absent bound means unbounded on that side, not zero** — Jackson omits nulls,
and an unconfigured band never rejects a package (FR-4.3).

### Listing employees

`GET /api/v1/employees` (ADMIN, HR) takes `q`, `departmentId`, `designationId`,
`gradeId`, `status`, plus the usual `page`, `size` and `sort`:

```bash
curl -s 'http://localhost:8080/api/v1/employees?q=asha&departmentId=1&sort=lastName,asc&size=20' \
  -H "Authorization: Bearer $TOKEN"
```

| Parameter | Meaning |
| --- | --- |
| `q` | Case-insensitive match against the employee's full name |
| `departmentId`, `designationId`, `gradeId` | Exact reference-data match |
| `status` | `ACTIVE_ONLY` (default), `INACTIVE_ONLY`, or `ALL` |
| `page`, `size` | Zero-based page, size capped at 100 |
| `sort` | `employeeCode`, `firstName`, `lastName`, `workEmail`, `dateOfJoining`, `exitDate`, `status`, `department`, `designation`, `grade` — each `,asc` or `,desc` |

Three things worth knowing about the contract:

- **Leavers are hidden by default.** Records are soft-deleted, so an unfiltered list would
  quietly include them; ask for `status=ALL` to see them.
- **An unlisted `sort` key is a 400, not an ignored parameter** — it would otherwise reach
  the query as a property path.
- **The response is a `PageResponse`**, not Spring's `Page`: `content`, `page`, `size`,
  `totalElements`, `totalPages`, `hasNext`, `hasPrevious`.

### Assigning compensation

A package is never edited. Assigning a new one supersedes the current revision, so the
history stays intact and payroll for an earlier month still sees that month's figures.

```bash
# What would this package come to? Nothing is saved.
curl -s -X POST http://localhost:8080/api/v1/employees/7/salary-structures/preview \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"effectiveFrom":"2026-04-01","components":[
        {"componentId":1,"value":"50000.00"},
        {"componentId":2,"value":"20000.00"},
        {"componentId":3,"value":"12"}]}'
# → {"basicMonthly":"50000.00","grossMonthly":"70000.00","totalDeductions":"6000.00",
#    "netMonthly":"64000.00","annualCtc":"840000.00"}
```

| Endpoint | Roles | Purpose |
| --- | --- | --- |
| `GET /api/v1/salary-components` | ADMIN, HR | List component definitions |
| `POST /api/v1/salary-components` | ADMIN | Define a component |
| `GET /api/v1/employees/{id}/salary-structures` | ADMIN, HR | Full revision history, newest first |
| `GET /api/v1/employees/{id}/salary-structures/current` | ADMIN, HR | The package in force, or 204 |
| `POST /api/v1/employees/{id}/salary-structures/preview` | ADMIN, HR | Validate and cost without saving |
| `POST /api/v1/employees/{id}/salary-structures` | ADMIN, HR | Assign, superseding the current one |

Rules the API enforces, all of them server-side:

- Every package needs a positive `BASIC` component; percentage components are computed
  against it.
- `effectiveFrom` may not precede the employee's date of joining, and a leaver's
  compensation cannot be changed at all.
- A package whose annual CTC falls outside the employee's grade band is rejected unless
  `overrideReason` is supplied — the reason is stored on the revision and in the audit
  trail.
- Deductions may not exceed gross pay.
- Amounts are rounded per component before summation, so the lines always add up to the
  totals.

### Compensation analytics

What the organisation's packages currently cost, with breakdowns by department and grade
(FR-7.4). ADMIN and HR only — an aggregate over salaries is not anonymous.

```bash
curl -s http://localhost:8080/api/v1/reports/compensation -H "Authorization: Bearer $TOKEN"
```

| Endpoint | Purpose |
| --- | --- |
| `GET /api/v1/reports/compensation` | Organisation figures plus both breakdowns |
| `GET /api/v1/reports/compensation/summary` | Organisation figures alone, for a dashboard tile |
| `GET /api/v1/reports/compensation/by-department` | Cost per department, most expensive first |
| `GET /api/v1/reports/compensation/by-grade` | Cost per grade, most expensive first |

Each group reports headcount, how many of those hold a package, totals for gross,
deductions, net and annual CTC, and the distribution — average, median, lowest and
highest monthly gross. Median sits beside average deliberately: a few senior packages
skew a mean badly.

Two things to read carefully:

- **This is not a payroll register.** It prices the packages in force *now* and knows
  nothing about attendance or loss of pay, so it will differ from an actual month's
  payroll wherever someone has unpaid days. The period-based register arrives with payroll
  runs.
- **Employees with no package are counted, not priced.** They appear as
  `employeesWithoutPackage` rather than being averaged in as zero — a payroll run would
  skip them, so the gap is the useful signal.

Errors share one envelope:

```json
{
  "timestamp": "2026-09-15T10:22:31Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/v1/employees",
  "fieldErrors": [{ "field": "workEmail", "message": "must be a valid email" }]
}
```

The endpoint table is in [docs/requirements.md](docs/requirements.md#6-api-surface).

## Frontend

An Angular 21 single-page application: standalone components, signals, zoneless change
detection, and every feature area lazy-loaded by route. Module-specific notes are in
[frontend/README.md](frontend/README.md); the structure is in
[architecture §6](docs/architecture.md#6-frontend-architecture).

### Screens

| Route | Roles | What it does |
| --- | --- | --- |
| `/login` | public | Sign in. Mirrors the server's validation, and shows the server's message on failure |
| `/employees` | ADMIN, HR | Employee list: name search, department/designation/grade filters, leaver visibility, sortable columns, paging, rows-per-page |
| `/employees/:id` | ADMIN, HR | One employee's record, read-only |
| `/not-authorised` | any | Shown when a signed-in user's role does not cover a route |

Everything above is server-driven. Nothing is filtered, sorted or paged in the browser, so
the counts and page numbers are real — sorting page 1 of 12 re-queries and returns the
first page of the new order rather than reordering the twenty rows in memory.

### The employee list keeps its state in the URL

There is no local copy of what the table is showing. Every control navigates, the criteria
are parsed back out of the query string, and the request pipeline watches that:

```
control → router.navigate → query string → criteria → request → table
```

```
/employees?q=asha&departmentId=1&status=ALL&page=3&size=50&sort=lastName&direction=desc
```

That indirection buys four things a local signal cannot, and that anyone reasonably
expects of a list screen: a filtered list is a **shareable link**, **reload** keeps your
place, **Back** undoes your last filter instead of leaving the screen, and a link pasted
into a ticket still means what it meant when it was written.

Four details are deliberate:

- **Defaults are omitted.** A freshly opened list is `/employees`, not
  `/employees?status=ACTIVE_ONLY&page=0&size=20&sort=employeeCode&direction=asc`. Only
  what differs is written, which is what keeps a shared link readable.
- **`page` is 1-based in the URL and 0-based in the API.** A link that read `page=2` and
  showed the third page would be a permanent small confusion for whoever shared it.
- **Typing replaces rather than pushes.** A ten-character search leaves one history entry,
  not ten — otherwise Back would walk the user back through their own typing. Every
  deliberate change (filter, sort, page, size) pushes, so Back is useful.
- **The query string is untrusted input.** Every value is validated and anything
  unrecognised falls back to its default. The sort key matters most: the API answers **400**
  for a key outside its whitelist rather than ignoring it, so forwarding `?sort=passwordHash`
  from a stale bookmark would produce an error page instead of a list. The translation lives
  in `employee-query.ts` and is tested directly.

One consequence worth naming: a link can point at a page that no longer exists, because the
data moved on. The server answers that with an empty page, and the screen says *"That page
no longer exists"* with a way back to the first page — rather than blaming filters the user
never set.

### Four decisions worth knowing before reading the code

- **Money is formatted from the string the API sent, never parsed into a `number`.** An
  IEEE-754 double cannot represent most decimal amounts exactly, so `shared/money.ts`
  inserts thousands separators by walking the characters. Longer than
  `Intl.NumberFormat`; it is the cost of the rule in
  [architecture §6.3](docs/architecture.md#63-money-and-locale-on-the-client).
- **Calendar dates are formatted the same way, and `DatePipe` is not used for them.**
  `new Date("2022-06-01")` is *UTC* midnight, which renders as 31 May anywhere behind
  UTC — every joining and exit date a day early for users west of Greenwich, on dates that
  payroll eligibility and proration depend on.
- **Interceptor order in `app.config.ts` is load-bearing.** Failures propagate
  innermost-first, so the auth interceptor is registered *after* the error interceptor in
  order to see a raw `HttpErrorResponse` and recognise a 401. Reversed, an expired token
  would silently fail to end the session.
- **The session lives in `localStorage`** (ADR-022), which means an XSS bug on this origin
  could read the token. That trade is stated rather than softened: what bounds it is the
  60-minute token and the `tokenVersion` check on the server, and the frontend's part of
  the bargain is not introducing the XSS.

### Accessibility and responsiveness

NFR-4.1 and NFR-4.2 are treated as requirements rather than aspirations: layouts are
single-column from 360px and widen at breakpoints, wide tables scroll in their own
keyboard-reachable region rather than pushing the page sideways, colour is never the only
signal (invalid controls also carry `aria-invalid` and a message), sort state is announced
via `aria-sort`, focus is always visible through `:focus-visible`, and a skip link is the
first focusable element on every page.

## Roles and permissions

| Capability | ADMIN | HR | EMPLOYEE |
| --- | :---: | :---: | :---: |
| Manage users and reference data | ✅ | — | — |
| Manage employees | ✅ | ✅ | — |
| Assign salary structures | ✅ | ✅ | — |
| Run and finalise payroll | ✅ | ✅ | — |
| View all payslips | ✅ | ✅ | — |
| View own profile, structure, payslips | ✅ | ✅ | ✅ |
| Query audit trail | ✅ | — | — |

Authorisation is enforced in the API. The UI hides what a role cannot do as a
convenience only — it is not a security control.

That split is visible in the code. `roleGuard('ADMIN', 'HR')` on the employees route and
the `canManageEmployees` check behind the menu both exist so an EMPLOYEE gets an
explanation instead of a screen filling with 403s. Neither is load-bearing: the matching
`@PreAuthorize` on the endpoint is, and `TokenAuthenticationFlowTest` proves an EMPLOYEE
token is refused by the API whatever the browser believed.

## Testing

```bash
cd backend  && ./mvnw test                   # unit + integration (Testcontainers)
cd frontend && npm test                      # unit tests (Vitest + Angular TestBed)
cd frontend && npm run lint                  # ESLint, TypeScript and templates
```

`./mvnw test` runs both unit tests and the `*IT` integration tests. The integration tests
start a real PostgreSQL container (ADR-012) and **skip themselves when Docker is not
running** — so a green build without Docker has not verified the constraints, column
types, or queries. Start Docker to exercise those.

Two tests cover part of that gap with no database at all:

- `SchemaMappingConsistencyTest` builds Hibernate's mapping metadata offline and fails if a
  mapped table or column is missing from `db/migration` — the mismatch that would
  otherwise stop startup under `ddl-auto: validate`.
- `DevSeedFiguresTest` parses the dev seed SQL, prices the seeded packages with the real
  calculator, and asserts the figures quoted above. Edit the seed and the documentation
  stops being wrong quietly.

One backend test is worth naming, because it covers the gap the others leave.
`TokenAuthenticationFlowTest` drives a token minted by the real login through the real
filter chain onto a real protected endpoint. The other security tests each verify one
link — a token round-trips, claims resolve to a caller, a role rule holds for a principal
conjured by `@WithMockUser` — and all of them would pass while every request still
answered 401, because none of them proves the links are joined.

On the frontend, the tests that earn their place are the ones guarding a decision that is
easy to undo by accident:

- `money.spec.ts` asserts that amounts are formatted from the string the API sent, never
  parsed into a `number`. It includes values with more significant digits than a double
  holds exactly, so an implementation that reached for `Intl.NumberFormat` would fail.
- `dates.spec.ts` covers the timezone trap: `new Date("2022-06-01")` is *UTC* midnight, so
  a `DatePipe` would render every joining date a day early for anyone west of Greenwich.
- `auth.interceptor.spec.ts` registers the interceptors in the same order as
  `app.config.ts`, because that order is what lets the auth interceptor see a raw
  `HttpErrorResponse`. Tested in isolation it would pass while the app did not.

Coverage targets: 70% overall on the backend, 90% in the payroll calculation package —
that is where the money is computed, so it carries the strictest bar.

## Project conventions

- **Money is `BigDecimal`**, stored as `NUMERIC(12,2)`. Never `double` or `float`.
- **Rounding** is half-up to two decimals, applied per component, so a payslip total
  always equals the sum of its displayed lines.
- **Schema changes** are additive Flyway migrations under
  `backend/src/main/resources/db/migration`, named `V{n}__{description}.sql`. Applied
  migrations are never edited; `ddl-auto` stays `validate`.
- **DTOs at the boundary.** Entities are not serialised to clients.
- **Immutability where it matters.** Finalised payroll runs, their payslips, and any
  salary structure they reference cannot be modified — corrections are made by cancelling
  a draft and re-running.
- **Branches** are `feature/<short-description>`; commits use Conventional Commits
  (`feat:`, `fix:`, `docs:`, `test:`, `refactor:`).

## Documentation

Three documents, in the order worth reading them:

- [docs/requirements.md](docs/requirements.md) — **what** the system must do. Full SRS:
  scope, functional requirements (`FR-*`), non-functional requirements (`NFR-*`), data
  model, API surface, acceptance criteria, and what is deliberately out of scope.
- [docs/architecture.md](docs/architecture.md) — **how** it is built. Container and
  layering views, package structure, the payroll engine and its calculation pipeline,
  data and security architecture, deployment, testing strategy, and known weaknesses.
- [docs/decisions.md](docs/decisions.md) — **why**, and what each choice cost. Twenty-two
  decision records (`ADR-*`) with rejected alternatives, consequences, and revisit
  triggers, plus a consolidated tradeoff summary.

## Roadmap

- [x] Backend scaffold: Spring Boot project, Flyway baseline, health endpoint, error
      envelope, money helpers, deny-by-default security chain
- [x] Employee and reference-data domain: entities, repositories, search specifications,
      JPA auditing
- [x] Employee search: paged, filtered, sorted list endpoint with a sort whitelist and a
      page-size cap
- [x] Salary components: definitions with flat and percent-of-basic calculation
- [x] Salary structures: effective-dated packages, revision history, grade-band guard,
      totals preview, audit trail
- [x] Compensation analytics: current salary cost with department and grade breakdowns,
      coverage gaps, and distribution statistics
- [x] Auth: login, refresh, JWT filter, role-based method security — the API is now
      reachable with a token, and `@PreAuthorize` applies to real callers
- [x] Reference-data read endpoints: departments, designations, grades
- [x] Angular shell: routing, lazy feature loading, auth and role guards, the three
      interceptors, role-aware navigation
- [x] Angular employees: paged/filtered/sorted list and a read-only detail view
- [x] Employee list state in the URL, so a filtered list is a shareable link and Back
      undoes the last filter; rows-per-page selector
- [ ] Employee write endpoints: create, update, deactivate — and the Angular form that
      needs them. The detail screen is read-only until these exist
- [ ] Angular structures: revision history and the assignment form with live preview
- [ ] Angular compensation dashboard over `/reports/compensation`
- [ ] Change own password (FR-1.6) — `tokenVersion` already invalidates tokens on change
- [ ] Employee self-service `/me` endpoints and the ownership checks they need
- [ ] Payroll run engine with proration and draft/finalise states
- [ ] Payslip views and PDF export
- [ ] Reference-data write endpoints (ADMIN) and the audit-trail query endpoint
- [ ] CI pipeline: build, test, lint on every push
