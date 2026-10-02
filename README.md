# ACME Salary Management

A web application for managing employee compensation at ACME: HR maintains employees and
their salary structures, runs a monthly payroll cycle, and publishes payslips that
employees can view and download for themselves.

**Status:** in progress, and now end-to-end for the part that exists. Authentication is
implemented — login issues a JWT, the filter chain accepts it, and the `@PreAuthorize`
rules on every endpoint apply to real callers — so the API is reachable with curl rather
than only from tests. Employee master data, reference data, salary component definitions
and effective-dated salary structures are implemented, along with compensation analytics,
the audit trail and the paging contract. The development database seeds **10,000
employees**, which is the scale the screens and the queries are meant to be judged at.

The Angular frontend now covers **every screen whose API exists**: login and a role-aware
shell, the paged/filtered/sorted employee list with its state in the URL, the employee
record with its compensation and full revision history, the package assignment form with a
live server-side preview, the compensation dashboard, the salary component definitions, and
starting a payroll run.

The **payroll cycle is now complete end to end**: HR starts a run for a completed month,
reviews the draft payslip by payslip, enters loss-of-pay days and recomputes, then
finalises to publish or cancels to abandon. **Employee records are writable** too — create,
edit and record an exit, all from the UI. Still missing: payslip PDF export, the HR-facing
payslip list, change password, and the audit trail query.

An EMPLOYEE now has screens of their own — their payslip list and a payslip view — so all
three roles land somewhere useful. Until this turn, an employee signed in successfully and
was bounced to the no-access page, because every route was ADMIN/HR.

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
│       │   ├── payroll/             runs, payslips + the pure payslip calculator
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
│   ├── proxy.conf.mjs               dev-server proxy: /api → localhost:8080, 502 on refusal
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
│           ├── shared/              money/rate and date formatting, paging, empty state
│           └── features/
│               ├── auth/login/      sign-in screen
│               ├── employees/       list (filter · sort · page) and detail
│               ├── structures/      salary history + the assignment form with live preview
│               ├── components/      salary component definitions (create is ADMIN-only)
│               ├── payroll/         start a run + the period arithmetic behind the picker
│               ├── payslips/        my payslips + one payslip, for any role
│               ├── reports/         compensation dashboard
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

Flyway applies migrations on startup. Under the `dev` profile three seed migrations also
load a working dataset — **10,000 employees** in total; that seed location is excluded from
every other profile, so fixtures can never reach a deployed schema.

| Seed | Contents |
| --- | --- |
| `V900` | Reference data — four departments, six designations, four grades with CTC bands, seven salary components |
| `V901` | Twelve **curated** employees, ids `1001`–`1012`: nine packages, one raise history, one leaver, two with no package |
| `V902` | 9,988 **generated** employees, `E-1013`–`E-11000`, taking the organisation to 10,000 |

Both employee seeds are **deterministic**: there is no `random()` anywhere, so employee
`1001` and employee `E-5000` are the same people with the same salaries on every machine
and after every reset. That is what lets the figures below be asserted rather than
described.

### The curated twelve (`V901`)

Small enough to read as literal rows, and where the interesting paths live: `E-1001` has a
**superseded revision** plus a current one (a raise, so the history screen has something to
show); `E-1010` and `E-1011` have **no package**, which compensation analytics reports as a
coverage gap; `E-1012` is a **leaver** whose package stays on record but is excluded from
current cost.

| What it shows | Figure |
| --- | --- |
| Headcount / active / active with a package | 12 / 11 / 9 |
| Total active monthly gross | 950,000.00 |
| Total deductions / net | 58,800.00 / 891,200.00 |
| By department | Engineering 580,000 · Finance 140,000 · Sales 140,000 · HR 90,000 |
| Median / average monthly gross | 90,000.00 / 105,555.56 |

`DevSeedFiguresTest` parses that SQL, prices it with the real calculator and asserts every
figure in the table, so an edit to the seed fails the build rather than quietly making this
documentation wrong.

### The generated remainder (`V902`)

Ten thousand is the size the problem statement describes, and most of this system's
decisions — paging in the database, the sort whitelist, aggregate reporting, the cost of a
payroll run — only become visible at that size. Rather than a multi-megabyte dump of
literal rows that nobody can review, `V902` derives every value from the row number in
about a hundred readable lines.

| What it produces | Figure |
| --- | --- |
| Generated employees | 9,988 (10,000 with the curated twelve) |
| Leavers | 102 — every 97th row, `INACTIVE` with an exit date |
| Without a package | 39 generated, 41 including `E-1010` and `E-1011` |
| Salary structures / lines | 9,949 / 59,694 |
| Grade mix | 45% G1 · 35% G2 · 15% G3 · 5% G4 |
| Department mix | 50% Engineering · 20% Finance · 10% HR · 20% Sales |
| Monthly gross | 37,000 to 321,000 across 243 distinct values |
| Joining dates | 1 Jan 2015 to 1 Jul 2026, each of 4,200 days used once |

Salaries are derived from each employee's **own grade band**, spread across the middle 80%
of it, so annual CTC always sits inside `[min_ctc, max_ctc]` and FR-4.3 would never have
demanded an override — the same property the curated seed has, at scale. Designations fit
the department and seniority fits the grade, so a G4 engineer is a manager rather than a
junior on a manager's salary.

**The seed proves itself.** A `DO` block at the end of `V902` re-costs every package in the
database the way the calculator does and raises an exception — failing startup — if the
employee count is wrong, or any package has a non-positive basic, falls outside its grade
band, or deducts more than it pays. A demo dataset the application would itself reject is a
trap for whoever reads it next.

One consequence worth stating plainly: a payroll run over this dataset computes close to
9,900 payslips in a single transaction (ADR-011), where NFR-1.3 budgets 60 seconds for
1,000. This seed is precisely what makes that budget worth revisiting, and the run review
screen renders one row per payslip, so it wants paging before anyone runs a month here.
Both are known and recorded rather than discovered in a demo.

The seeds are ordinary SQL, so they do not need the application to run. Against an
already-migrated database, in order:

```bash
cd backend/src/main/resources/db/seed
for f in V900__dev_seed.sql V901__dev_employee_seed.sql V902__bulk_employee_seed.sql; do
  psql -h localhost -U salary_app -d salary_mgmt -f "$f"
done
```

Every statement is idempotent (`ON CONFLICT DO NOTHING`), so a re-run is safe. `V902`
depends on the first two — it resolves departments, grades and the HR author by natural
key, and its self-check expects the curated twelve to be present.

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
equally, while `asha.menon@acme.test` is an EMPLOYEE and lands on their own payslips —
listing everyone is not an employee's endpoint (FR-1.5).

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
| `POST /api/v1/auth/change-password` | any authenticated | Change your own password (FR-1.6) |

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
- **Changing a password is the one thing that really does end every session.** It moves the
  user's `tokenVersion`, which the filter compares on every request, so a stolen token dies
  with the password behind it — on every device, including the one that made the request.
  The endpoint takes no user id, so it cannot be aimed at another account, and a wrong
  current password is a **400 naming the field rather than a 401**: the caller is
  authenticated, so this is a failed confirmation, and a 401 would make the client's
  interceptor end the session over a typo.

### Payroll runs

A run computes a month's pay for everyone eligible, as a reviewable draft, then publishes
it. ADMIN and HR only — a draft holds every salary in the organisation.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/v1/payroll-runs` | Start a draft run for a period, computing every payslip |
| `GET /api/v1/payroll-runs` | Runs newest period first, with totals; no payslips |
| `GET /api/v1/payroll-runs/{id}` | One run with every payslip in it |
| `POST /api/v1/payroll-runs/{id}/recompute` | Recompute a draft, applying LOP adjustments |
| `POST /api/v1/payroll-runs/{id}/finalise` | Publish the payslips already computed |
| `POST /api/v1/payroll-runs/{id}/cancel` | Abandon a draft and free the period |

```bash
curl -s -X POST http://localhost:8080/api/v1/payroll-runs \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"periodYear":2026,"periodMonth":4}'
```

**How proration works** (FR-5.4). Paid days are the period's calendar days less the
loss-of-pay days, and the treatment differs by component:

| | `FLAT` | `PERCENT_OF_BASIC` |
| --- | --- | --- |
| Earning | prorated | percentage of the **prorated** basic |
| Deduction | **not** prorated | percentage of the **prorated** basic |

This is the pipeline [architecture §5.2](docs/architecture.md#52-calculation-pipeline)
already specified, step for step, including `FLAT → value as-is` for deductions — a flat
deduction such as professional tax is a fixed statutory charge that does not shrink because
someone took unpaid leave. A percentage deduction needs no proration of its own: 12% of an
already-prorated basic follows attendance down exactly once, and prorating it again would
reduce it twice.

Six things the engine does that are worth knowing:

- **Payslips exist from creation, not from finalisation** (FR-5.8). Finalising publishes
  what was reviewed *without recomputing it*, which is what makes the review mean anything.
- **A period may only be run once it is over.** Not in the requirements, and defensible
  anyway: proration divides by the days in the month, so running March on the 10th would
  pay a full month for a month that has not happened.
- **A second run for a period is a 409** (FR-5.7). The pre-check gives a readable message;
  the partial unique index `uq_payroll_runs_active_period` is what actually prevents it
  under a race — and it counts only DRAFT and FINALISED, so a cancelled run does not block
  a retry.
- **Adjustments are the whole picture, not a delta.** An employee absent from the
  `recompute` list is recomputed at full attendance, which is how a mistaken LOP entry is
  undone. As a patch there would be no way to express "clear this".
- **Someone eligible but without a package is skipped, not given a zero payslip** — the
  same treatment compensation analytics gives them.
- **A leaver is included for the month they left.** Eligibility is "joined on or before the
  period ends and not gone before it begins" (FR-2.6), which is broader than "active".

The whole run is one transaction (FR-5.9), which is why ADR-011 chose a synchronous run: a
failure halfway must leave no partial run, and inside one transaction the rollback is the
database's problem rather than a compensating-action problem. The cost is a request that
grows with headcount — NFR-1.3 budgets 60 seconds for 1,000 employees.

### Payslips

| Endpoint | Roles | Purpose |
| --- | --- | --- |
| `GET /api/v1/payslips/me` | any authenticated | The caller's own published payslips, newest first |
| `GET /api/v1/payslips` | any authenticated | Paged search: run, period, department, employee (FR-6.5) — the register when given a period (FR-7.1) |
| `GET /api/v1/payslips/{id}` | ADMIN, HR, owner | One payslip |
| `GET /api/v1/payslips/{id}/pdf` | ADMIN, HR, owner | The same payslip as a one-page PDF (FR-6.4) |
| `GET /api/v1/payroll-runs/{id}/payslips` | ADMIN, HR | The payslips in one run, paged — the register for its period |

```bash
curl -s http://localhost:8080/api/v1/payslips/me -H "Authorization: Bearer $TOKEN"
```

**This is where record-level ownership is enforced.**
[Architecture §8.1](docs/architecture.md#81-layers-of-defence) calls it the layer most often
missed: a role check answers *"may an EMPLOYEE read payslips?"* but not *"may **this**
employee read **this** payslip?"*. `@PreAuthorize` cannot answer the second — it does not
know whose payslip it is until the row is loaded — so the decision lives in the service,
against the authenticated principal (FR-1.5).

The rules:

- **ADMIN and HR may read any payslip**, draft included, because reviewing a draft run is
  their job (FR-5.6).
- **An EMPLOYEE may read only their own, and only once published.** A draft payslip has not
  been published and its figures may still change (FR-5.8), so being its subject is not yet
  grounds to see it. The same row HR is reviewing is refused to the person it is about.

Three details worth knowing:

- **`/me` takes no id**, which is the point of the path: there is nothing in the URL to
  tamper with, so the endpoint cannot be aimed at anyone else however the request is built.
- **A payslip you may not have answers 404, not 403.** A 403 would confirm the id exists,
  turning `/payslips/{id}` into a way to probe how many payslips there are and whose. The
  caller learns the same thing either way — they cannot have it — and the log records the
  real reason.
- **`/me` is open to every authenticated role, not just EMPLOYEE.** An HR user who is also
  on the payroll has payslips of their own, and "my own data" is not a privilege to
  withhold. A caller with no employee record — an ADMIN login provisioned without one —
  gets an empty list rather than an error.

**The search narrows itself by role, in the query.** ADMIN and HR see every payslip, drafts
included. Any other caller is restricted to their own published rows *in the database*
rather than filtered afterwards — so `GET /payslips?employeeId=<somebody else>` returns
their own payslips rather than a refusal, and the page totals cannot disclose how many rows
they were not allowed. `periodYear` and `periodMonth` are only honoured together, because
"March" of no particular year would match every March on record.

**The PDF is the same read, rendered differently.** It reuses the service's ownership
check rather than restating it, so a payslip the caller may not have answers 404 there too,
and a draft is stamped `DRAFT` on the document itself — HR downloads drafts while reviewing
a run, and a page that did not say so could be handed to an employee as final. Two
compromises are visible in the output: amounts read `INR 1,40,800.00` rather than using the
rupee sign, because the standard PDF fonts are WinAnsi and embedding a Unicode font for one
glyph is disproportionate; and a character WinAnsi cannot encode is replaced rather than
refused, because a download that fails is worse than a transliteration gap. The digits are
grouped the Indian way (NFR-4.4), which the renderer has to do itself — a server-rendered
document cannot borrow the browser's formatter.

A payslip carries the employee's identity and the period so it stands alone (FR-6.2), and
its figures come from the payslip's own columns, never from the employee's current package.
A payslip is the record of what was paid; reading from the package in force would make a
two-year-old payslip change when somebody gets a raise.

### Reference data

`GET /departments`, `/designations` and `/grades` (ADMIN, HR) return the vocabulary an
employee record is expressed in. Unpaged: these are three closed, small lists whose only
consumer needs all of each or none of it, and they do not grow with headcount.

Writing them is **ADMIN only** — `POST`/`PUT` on each of the three (FR-3.1 to FR-3.3).
Reading stays open to HR because a form cannot be filled in without the options it offers;
adding a department changes the vocabulary every record and report is expressed in, which
is a different kind of decision.

There is no `DELETE` on any of them, and that is the design rather than an omission:
employees reference these rows with `ON DELETE RESTRICT`, so a row in use cannot be removed
at all, and removing an unused one would orphan the history that names it. A department
that is no longer used simply stops being chosen. Asking for one anyway now answers **405
with an `Allow` header** rather than the 500 it used to — a test asserting the absence of
the endpoint is what found that.

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

### Writing employees

| Endpoint | Roles | Purpose |
| --- | --- | --- |
| `POST /api/v1/employees` | ADMIN, HR | Create a record (FR-2.1) |
| `PUT /api/v1/employees/{id}` | ADMIN, HR | Replace the editable fields (FR-2.3) |
| `POST /api/v1/employees/{id}/deactivate` | ADMIN, HR | Record an exit (FR-2.5) |

```bash
curl -s -X POST http://localhost:8080/api/v1/employees \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"employeeCode":"E-2001","firstName":"Ravi","lastName":"Iyer",
       "workEmail":"ravi.iyer@acme.test","dateOfJoining":"2026-04-01",
       "departmentId":1,"designationId":1,"gradeId":2}'
```

Five things the API decides here:

- **There is no `DELETE`, and there will not be one.** A payslip from years ago must still
  resolve the person it was for, so leaving is a `POST` to `/deactivate` carrying the exit
  date (ADR-014).
- **A duplicate is a 409 with the field named** (FR-2.2), and both duplicates are reported
  together when the code *and* the email are taken. The pre-check normalises the way the
  entity does — code uppercased, email lowercased — because a check against the raw input
  would let `e-2001` past a lookup for `E-2001`; the unique indexes remain what actually
  prevents a duplicate under a race.
- **The employee code and date of joining are immutable** (FR-2.3). They are not fields on
  the update request at all, and the entity maps them `updatable = false` so the mapping
  enforces it too.
- **The exit date is required, never defaulted**, and may be in the future. Payroll
  eligibility for a period is derived from it (FR-2.6), so a defaulted date would quietly
  decide whether someone is paid for the month they left. A second deactivation answers
  409 rather than moving the first date.
- **A bad reference is a 400 naming which one.** An unknown `gradeId` is not a missing
  employee, so it is a field error on `gradeId` rather than a 404.

Every one of the three writes records an audit event with the actor inside the same
transaction (ADR-013), using the `EMPLOYEE_CREATED` / `EMPLOYEE_UPDATED` /
`EMPLOYEE_DEACTIVATED` actions.

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

### Audit trail

`GET /api/v1/audit-events` (**ADMIN only**) filters by actor, entity type, entity, action
and date range, paged and sorted in the database (FR-8.2).

```bash
curl -s 'http://localhost:8080/api/v1/audit-events?entityType=PayrollRun&from=2026-09-01&to=2026-09-30' \
  -H "Authorization: Bearer $TOKEN"
```

This is the strictest read in the API, and more restricted than the salary data itself: one
page of it spans every feature, so HR's access to employee records deliberately does not
extend to the record of everybody's actions — including their own.

Four things worth knowing:

- **`from` and `to` are calendar days and both are inclusive**, so asking for the same date
  twice returns that whole day. Internally the range is half-open, which is what stops a
  row being counted twice by two adjacent queries. The boundaries are UTC, which is what
  the timestamps are stored and returned in.
- **The trail is append-only and there is no write endpoint.** Rows are written inside the
  transaction of the change they describe (ADR-013); a trail with an edit endpoint is not
  evidence of anything.
- **`details` is JSON, not a string of JSON.** A client that had to parse a string to read
  `totalNet` would be holding the database's storage format rather than an API.
- **The actor's address is resolved per page, not joined.** The trail records an actor id
  precisely so it does not depend on the account still existing, so a renamed or deleted
  login cannot change what the trail says happened — the row then reports the id alone.

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
| `/employees/new` | ADMIN, HR | Create a record. Duplicate code or email comes back as a 409 against the field |
| `/employees/:id` | ADMIN, HR | One employee's record, plus the package in force and every revision behind it; records an exit |
| `/employees/:id/edit` | ADMIN, HR | Edit the editable fields; the code and joining date are shown as immutable facts |
| `/employees/:id/salary-structures/new` | ADMIN, HR | Assign a package, with gross/net/CTC costed by the server as the figures are edited |
| `/payroll-runs` | ADMIN, HR | Every run, newest period first, with totals and status |
| `/payroll-runs/new` | ADMIN, HR | Start a run for a completed month; hands the draft to the review screen |
| `/payroll-runs/:id` | ADMIN, HR | Review a draft: per-employee loss-of-pay days, recompute, finalise or cancel |
| `/reports/compensation` | ADMIN, HR | What the packages in force cost, by department and by grade |
| `/salary-components` | ADMIN, HR | Component definitions; only ADMIN may define one |
| `/payslips` | any authenticated | My own payslips, newest first — the EMPLOYEE's home |
| `/payslips/all` | ADMIN, HR | Every payslip, filtered by period, department or employee; with a period chosen, the payroll register |
| `/payslips/:id` | any authenticated | One payslip as a document; the API enforces ownership |
| `/change-password` | any authenticated | Change your own password; signs you out, because the token it used is then dead |
| `/reference-data` | ADMIN | Departments, designations and grades with their CTC bands |
| `/audit` | ADMIN | Who changed what, and when — the one area HR cannot reach |
| `/not-authorised` | any | Kept for direct links; a guarded route now redirects to the user's own landing screen instead |

Everything above is server-driven. Nothing is filtered, sorted or paged in the browser, so
the counts and page numbers are real — sorting page 1 of 12 re-queries and returns the
first page of the new order rather than reordering the twenty rows in memory.

### Payslips, and the role-aware landing

`/payslips` is the EMPLOYEE's home and the fix for a real dead end: every route used to be
ADMIN/HR, so an employee signed in successfully and was bounced to the no-access page. The
root path now redirects by role (`landingRedirect`), and "My payslips" is the one nav item
available to everyone — an HR user on the payroll has payslips of their own, and "my own
data" is not a privilege to withhold.

The list shows the most recent payslip **in full**, because `GET /payslips/me` returns each
one complete: the API made a payslip stand alone so a PDF would need nothing else, so the
list needs no second request and opening one is a navigation rather than a round trip. The
payslip view still fetches by id, because a pasted link has to work without visiting the
list first.

Two things the screens are careful about:

- **A missing payslip and a forbidden one look identical.** The API answers 404 for a
  payslip the caller may not have, so a 403 cannot be used to confirm an id exists. The
  screen reports what it was told and does not speculate about which case it is.
- **Unpaid leave is explained, not left implicit.** A smaller month is the commonest
  surprise on a payslip, so both screens say how many days were unpaid and that earnings
  are prorated while fixed statutory deductions are not.

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

### Compensation on the employee screen

The employee record carries its compensation: the package in force with its totals and
line-by-line breakdown, and every superseded revision behind it, collapsed until asked for.

It is a separate component (`features/structures/`) on a separate endpoint, rendered beside
the identity data rather than inside it — so a failure to read the salary history, which has
stricter reasons to fail, still leaves the employee's record on screen.

Four things the screen is careful about:

- **Each table shows its own subtotal.** Amounts are rounded *per component* before being
  summed (NFR-3.3), which is precisely what makes the lines add up to the total exactly.
  Showing both is what makes the arithmetic checkable by hand — the property ADR-006 spends
  a little purity to buy.
- **A percentage is not money.** A `PERCENT_OF_BASIC` component's `configuredValue` is
  `"12.00"` meaning twelve per cent, so it renders as `12% of basic` alongside the
  `₹9,000.00` it produces. Through the money formatter it would read `₹12.00`, for what is
  actually a nine-thousand-rupee deduction — hence a separate `percentage` pipe, and a test
  asserting no rate ever carries a currency symbol.
- **An override reason is stated, not tucked away.** It is present only where a package was
  accepted outside the employee's grade CTC band (FR-4.3), so its presence is the signal.
- **No package is a gap, not an error.** An employee with none is what compensation
  analytics counts as `employeesWithoutPackage` and what a payroll run skips, so the screen
  says so and distinguishes it from a failed request.

Only the history endpoint is called. The response already flags the current revision, so
asking `/current` as well would be two sources that could disagree — and it answers 204 when
nothing is assigned, which is a second empty-state path for no gain.

### Assigning a package

The one genuinely interactive screen, and the one ADR-003 cites as a reason for choosing
Angular: gross, net and annual CTC update as the figures are edited.

**The server computes; the screen only asks.** Every figure comes from `POST …/preview`,
debounced on each edit. Nothing is added up in the browser — components are rounded
individually before being summed (NFR-3.3), so a total computed here would disagree with
the one that gets saved.

Because the preview runs the *same* validation as the assignment, it doubles as validation.
The screen has almost no business rules of its own: it does not know that a package needs a
positive `BASIC`, that deductions may not exceed gross, or where a grade's band sits. It
asks, and shows the answer. Two consequences:

- **The grade band asks for itself.** A package outside the employee's band is allowed, but
  only deliberately (FR-4.3). The server signals it by rejecting the preview with a field
  error on `overrideReason`, so the reason box appears exactly when one is needed, carrying
  the server's own account of which band was missed and by how much.
- **Saving waits on a successful preview.** Without one there is no evidence the assignment
  would be accepted, and FR-4.5 exists so nobody commits to a package sight unseen.

The form is pre-filled from the package in force, which makes a raise a small edit rather
than a re-entry of six figures. The effective date is deliberately left blank — it is the
one field that has to be a decision.

### Employee records

`/employees/new` and `/employees/:id/edit` are the same component: the two screens differ
by two fields and a verb, so splitting them would mean maintaining the same six controls,
the same reference-data load and the same error handling twice.

Four details are deliberate:

- **The code and joining date are immutable after creation** (FR-2.3), and in edit mode
  they appear as read-only facts with the reason beside them rather than as greyed-out
  inputs. A disabled box invites "why can't I?"; a value with a sentence under it answers
  the question — the code is on published payslips, and the joining date is what every
  salary revision is validated against.
- **Uniqueness belongs to the server.** A duplicate code or email cannot be checked in the
  browser at all, so it arrives as a 409 carrying a field-level message (FR-2.2) and is
  shown against the control it names. Both duplicates are reported together when both are
  taken, because a form you have to resubmit to learn the second fact is a worse form.
- **A padded value is trimmed when the field loses focus.** A pasted address arrives as
  `" asha@acme.test "`, which the email validator rejects — so without trimming the form
  would refuse a value it was about to trim and send anyway.
- **Recording an exit stays on the record screen**, not in the form: it is one date and a
  decision, and the thing being confirmed is the record in front of you. The panel says
  what the date will do — the employee stays in payroll for a period beginning on or
  before it, and drops out afterwards (FR-2.6) — and a second exit is refused rather than
  quietly moving the first, because that would be a payroll change disguised as a repeated
  click.

Nothing is ever deleted. There is no `DELETE` endpoint and no button asking for one: a
payslip from years ago must still resolve the person it was for (ADR-014).

### Running payroll

`/payroll-runs` lists every run newest first, `/payroll-runs/new` starts one, and
`/payroll-runs/:id` is where the month is actually finished.

The start screen is two dropdowns and a button, so its real work is explaining
what the button does. That is not decoration: a run computes a month's pay for the whole
organisation in one transaction, it takes the period for itself so a second attempt is a
409 until the first is cancelled, and it can take a minute over a thousand employees. None
of that is guessable from a spinner, so the screen says each of it — including that
somebody eligible without a package is skipped, that a leaver is included for the month
they left, and that loss of pay is applied by recomputing afterwards rather than here.

Two details there are deliberate:

- **The month defaults to the month just gone**, which is what payroll is nearly always
  being run for, and the period arithmetic behind that lives in `payroll-period.ts` and is
  tested directly. The month before January is in the previous year and February is 28 days
  or 29; each of those getting it wrong would offer the wrong month to run.
- **"A period may only be run once it is over" is mirrored in the form**, so the button
  explains itself before a request rather than after a 400. It is a mirror and not a
  replacement: the browser's clock is not authoritative, and where the server disagrees its
  message is what gets shown.

A started run hands straight over to the **review screen**, because a draft is somebody's
next action rather than a result to admire. That screen shows the run's totals and one row
per payslip — the figures finalising would publish *unchanged* (FR-5.8), which is the only
thing that makes reviewing them mean anything — and carries the rest of the cycle:

- **Loss of pay is entered per employee, then recomputed.** Earnings prorate over the
  month while fixed statutory deductions do not (FR-5.4), and the screen says so where the
  days are entered. Recompute is offered only once something has actually changed.
- **The adjustments are sent as the whole picture, never a delta** (FR-5.6). An employee
  absent from the request is recomputed at full attendance, which is precisely how a
  mistaken entry is cleared — as a patch there would be no way to express it. So a zero in
  the box is *omitted* from the request rather than sent as zero.
- **Anything irreversible takes two clicks**, and the confirmation says what will happen in
  the words of the thing that will happen: "*{n} payslips totalling {amount} become visible
  to the employees they belong to*", not "are you sure?". Finalising publishes; cancelling
  abandons the draft and frees the period.
- **A finalised or cancelled run is read-only**, with the reason on screen: published
  payslips are immutable, and a correction means a later period rather than an edit
  (FR-6.6).
- **The payslip table is paged in the browser, and says so.** The API returns the whole run
  in one response, so at ten thousand employees the rows are split client-side to keep them
  out of the DOM — a stopgap the screen names rather than hides, because the real fix is a
  paged payslip endpoint.

### The compensation dashboard

Organisation totals as stat tiles, then department and grade breakdowns as **tables with an
inline share bar**. The form is deliberate: four or five rows of a single measure would be
worse as a pie (close values get harder to compare) and worse as a bare chart (it would hide
the figures people need). The table keeps the numbers readable and the bar makes the
magnitudes comparable — and it is inherently the table view accessibility asks for.

Every bar is the **same** hue. Shading each one by its own value would encode the same
number twice, since the category is already named in the row. The hue is the accent,
checked for lightness, chroma and contrast against the chart surface rather than eyeballed.

Two things the screen is careful to say: it is **not a payroll register** — it prices the
packages in force and knows nothing about attendance or loss of pay — and the **median sits
beside the average** because a few senior packages skew a mean.

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
the `canManageEmployees` check behind the menu both exist so an EMPLOYEE is sent to
their own payslips instead of a screen filling with 403s. Neither is load-bearing: the matching
`@PreAuthorize` on the endpoint is, and `TokenAuthenticationFlowTest` proves an EMPLOYEE
token is refused by the API whatever the browser believed.

## Testing

```bash
cd backend  && ./mvnw test                   # 663 tests: unit + integration (Testcontainers)
cd frontend && npm test                      # 537 tests (Vitest + Angular TestBed)
cd frontend && npm run lint                  # ESLint, TypeScript and templates
```

**CI runs all of that, plus two things a laptop usually cannot.** Docker is present on a
GitHub runner, so the 53 `*IT` tests execute instead of skipping — that is the only place
the Flyway schema, the column types and the hand-written queries are actually exercised.
A third job applies the 10,000-employee seed to a real PostgreSQL, asserts every count this
README documents, then applies it again to prove the seeds are idempotent as their headers
claim. See [.github/workflows/ci.yml](.github/workflows/ci.yml).

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

On the payroll engine the tests are weighted to where money goes wrong rather than to
line coverage. `PayslipCalculatorTest` covers the proration rule per component kind, the
invariant that displayed lines always sum to displayed totals at every LOP value, and the
edge that matters most: a package valid at full attendance can become **unpayable** under
proration, because the earnings shrink while a flat deduction stays put. That case is real
— the seeded package cannot be computed at 30 days of loss of pay — and it was the test
finding, not a guess.

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

Four documents, in the order worth reading them:

- [docs/requirements.md](docs/requirements.md) — **what** the system must do. Full SRS:
  scope, functional requirements (`FR-*`), non-functional requirements (`NFR-*`), data
  model, API surface, acceptance criteria, and what is deliberately out of scope.
- [docs/architecture.md](docs/architecture.md) — **how** it is built. Container and
  layering views, package structure, the payroll engine and its calculation pipeline,
  data and security architecture, deployment, testing strategy, and known weaknesses.
- [docs/decisions.md](docs/decisions.md) — **why**, and what each choice cost. Twenty-two
  decision records (`ADR-*`) with rejected alternatives, consequences, and revisit
  triggers, plus a consolidated tradeoff summary.
- [docs/ai-usage.md](docs/ai-usage.md) — **how it was built**: the tooling, the prompting
  patterns that produced the ADRs and the tests, what was verified and by what means,
  where the AI was wrong and what caught it, and what remains unverified.

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
- [x] Angular compensation: the package in force with its line-by-line breakdown, and the
      full revision history, on the employee record
- [x] Employee write endpoints: create, update, deactivate, with the Angular form and the
      exit panel that drive them
- [x] Angular structures: the assignment form with live server-side preview, the
      grade-band override prompt, and the `POST` that supersedes the current revision
- [x] Angular compensation dashboard over `/reports/compensation`
- [x] Angular salary components: definitions list, with create gated to ADMIN
- [ ] Employee self-service for the *profile* and salary structure (`/employees/{id}`
      and structures for self); payslips are done
- [x] 10,000-employee seed: generated deterministically from the row number, in-band by
      construction, and self-verifying
- [x] Payroll run engine: proration, the draft/finalise/cancel state machine, atomic
      runs, and the period guard
- [x] Payslip reads: `/payslips/me` and `/payslips/{id}` with record-level ownership
- [x] Angular payslips: my payslips and a payslip view, plus a role-aware landing so an
      EMPLOYEE has somewhere to go
- [x] Angular payroll, the whole cycle: the run list, the start-run screen, and a draft
      review screen with loss-of-pay adjustments, recompute, finalise and cancel
      (FR-5.1 to FR-5.10)
- [x] Paged payslip search (FR-6.5) doubling as the payroll register (FR-7.1), and a paged
      per-run endpoint so the review table is no longer split in the browser
- [x] Payslip PDF export (FR-6.4), reusing the ownership check and stamping a draft
- [x] Change own password (FR-1.6), and an EMPLOYEE login provisioned with each employee
      record (FR-2.7)
- [x] Audit-trail query for ADMIN (FR-8.2), and reference-data writes (FR-3.1 to FR-3.3)
- [x] CI pipeline: build, test and lint on every push — and the job that runs the
      Testcontainers tests and applies the 10,000-employee seed to a real PostgreSQL
- [ ] Deploy the single artifact (ADR-018 is decided but unbuilt: the Angular bundle is not
      yet packaged into the jar, and there is no `demo` profile to seed a hosted database)
      and record the demo
- [ ] Measure a 10,000-employee run against the re-budgeted NFR-1.3. The elapsed time and
      payslip count are now logged for every run, so this is a reading rather than an
      argument — but it needs a database this machine does not have
