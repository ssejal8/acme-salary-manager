# ACME Salary Management

A web application for managing employee compensation at ACME: HR maintains employees and
their salary structures, runs a monthly payroll cycle, and publishes payslips that
employees can view and download for themselves.

**Status:** early scaffold. The repository structure and the requirements are in place;
the backend and frontend are not implemented yet. Commands below describe the intended
developer workflow and will work as each module lands.

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
acme-salary-management/
├── backend/          Spring Boot REST API (Maven project)
├── frontend/         Angular single-page application
├── docs/
│   └── requirements.md   Software requirements specification
└── README.md
```

## Prerequisites

- **JDK 17** — `java -version` should report 17.x
- **Maven 3.9+** — or use the `./mvnw` wrapper once the backend is scaffolded
- **Node.js 20+** and npm 10+
- **Docker** and Docker Compose — for PostgreSQL locally
- **PostgreSQL 15+** — only if you prefer running the database outside Docker

## Getting started

Clone, then start the database:

```bash
git clone <repository-url>
cd acme-salary-management
docker compose up -d db          # PostgreSQL on localhost:5432
```

Run the backend:

```bash
cd backend
cp .env.example .env             # then edit the values — see Configuration
./mvnw spring-boot:run           # API on http://localhost:8080
```

Flyway applies migrations on startup. With the `dev` profile active, a seed migration
creates reference data and an admin login.

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

curl -s http://localhost:8080/api/v1/employees?page=0&size=20 \
  -H "Authorization: Bearer $TOKEN"
```

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

- [ ] Backend scaffold: Spring Boot project, Flyway baseline, health endpoint
- [ ] Auth: login, JWT filter, role-based method security
- [ ] Reference data and employee CRUD
- [ ] Salary components and salary structures with revision history
- [ ] Payroll run engine with proration and draft/finalise states
- [ ] Payslip views and PDF export
- [ ] Angular shell: routing, auth guard, token interceptor
- [ ] Angular feature modules: employees, structures, payroll, payslips
- [ ] Reports and dashboard
- [ ] CI pipeline: build, test, lint on every push
