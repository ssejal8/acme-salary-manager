# ACME Salary Management

A web application for managing employee compensation at ACME: HR maintains employees and
their salary structures, runs a monthly payroll cycle, and publishes payslips that
employees can view and download for themselves.

**Status:** backend in progress. Employee master data, salary component definitions and
effective-dated salary structures are implemented — domain, persistence, and read/assign
endpoints — along with the audit trail and the paging contract. Payroll runs, payslips and
the frontend are not built yet.

Authentication is the next piece, so **every business endpoint currently answers 401**:
the filter chain denies by default and no token issuer exists yet. The endpoints are
exercised by tests with a mocked principal rather than by curl. Commands below describe
the intended developer workflow; the frontend ones will work as that module lands.

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
| Auth | JWT bearer tokens, BCrypt password hashing |
| API docs | springdoc-openapi (Swagger UI) |
| Frontend | Angular 17+, TypeScript, RxJS, Angular Router |
| Build | Maven (backend), npm + Angular CLI (frontend) |
| Tests | JUnit 5 + Mockito + Testcontainers (backend), Jasmine/Karma (frontend) |
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
│       │   ├── orgdata/             departments, designations, grades
│       │   ├── salarycomponent/     component definitions (earnings, deductions)
│       │   ├── report/              compensation analytics (cost, department, grade)
│       │   ├── salarystructure/     effective-dated packages + the pure calculator
│       │   └── security/            user read model, current-user port, 401/403 responders
│       ├── main/resources/
│       │   ├── application.yml      + application-{dev,prod}.yml
│       │   ├── db/migration/        Flyway migrations (V1 baseline schema)
│       │   └── db/seed/             dev-profile-only fixtures
│       └── test/java/com/acme/salary/
├── frontend/                        Angular single-page application (not started)
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
- **Node.js 20+** and npm 10+
- **Docker** and Docker Compose — for PostgreSQL locally
- **PostgreSQL 15+** — only if you prefer running the database outside Docker

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

Flyway applies migrations on startup. Under the `dev` profile a seed migration also loads
reference data (departments, designations, grades, salary components) and the two logins
below; that seed location is excluded from every other profile, so fixtures can never
reach a deployed schema.

Run the frontend in a second terminal:

```bash
cd frontend
npm install
npm start                        # SPA on http://localhost:4200
```

The dev server proxies `/api` to `http://localhost:8080`, so no CORS configuration is
needed locally.

**Default dev credentials** (seeded by the `dev` profile only — never enabled in any
deployed environment):

| Role | Email | Password |
| --- | --- | --- |
| ADMIN | `admin@acme.test` | `Admin@123` |
| HR | `hr@acme.test` | `Hr@12345` |

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

## Testing

```bash
cd backend  && ./mvnw test                   # unit + integration (Testcontainers)
cd frontend && npm test                      # unit tests
cd frontend && npm run lint
```

`./mvnw test` runs both unit tests and the `*IT` integration tests. The integration tests
start a real PostgreSQL container (ADR-012) and **skip themselves when Docker is not
running** — so a green build without Docker has not verified the constraints, column
types, or queries. Start Docker to exercise those.

`SchemaMappingConsistencyTest` covers part of that gap with no database at all: it builds
Hibernate's mapping metadata offline and fails if a mapped table or column is missing from
`db/migration`, which is the mismatch that would otherwise stop startup under
`ddl-auto: validate`.

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
- [docs/decisions.md](docs/decisions.md) — **why**, and what each choice cost. Twenty
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
- [ ] Auth: login, JWT filter, role-based method security — until this lands, every
      employee endpoint answers 401 to an unauthenticated caller
- [ ] Employee write endpoints: create, update, deactivate
- [ ] Reference-data endpoints: departments, designations, grades
- [ ] Payroll run engine with proration and draft/finalise states
- [ ] Payslip views and PDF export
- [ ] Angular shell: routing, auth guard, token interceptor
- [ ] Angular feature modules: employees, structures, payroll, payslips
- [ ] Reports and dashboard
- [ ] CI pipeline: build, test, lint on every push
