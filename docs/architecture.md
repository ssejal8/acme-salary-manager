# ACME Salary Management — Architecture

**Version:** 0.1 (draft)
**Status:** Proposed — describes the intended design, not yet implemented code
**Last updated:** 2026-09-16

Companion documents: [requirements.md](requirements.md) defines *what* the system must
do; this document defines *how* it is built; [decisions.md](decisions.md) records *why*
each significant choice was made and what it cost.

---

## Contents

- [1. Architectural goals](#1-architectural-goals)
- [2. System context](#2-system-context)
- [3. Container view](#3-container-view)
- [4. Backend architecture](#4-backend-architecture)
- [5. The payroll engine](#5-the-payroll-engine)
- [6. Frontend architecture](#6-frontend-architecture)
- [7. Data architecture](#7-data-architecture)
- [8. Security architecture](#8-security-architecture)
- [9. Cross-cutting concerns](#9-cross-cutting-concerns)
- [10. Deployment](#10-deployment)
- [11. Testing strategy](#11-testing-strategy)
- [12. Scalability and evolution](#12-scalability-and-evolution)
- [13. Known weaknesses](#13-known-weaknesses)

---

## 1. Architectural goals

Ranked. Where two goals conflict, the higher one wins, and the conflict is recorded in
[decisions.md](decisions.md).

1. **Correctness of money.** A payslip must be arithmetically exact, reproducible, and
   immutable once published. Every other goal yields to this one.
2. **Auditability.** Who changed which compensation figure, and when, must always be
   answerable. Salary history is append-only.
3. **Confidentiality.** Salary data is among the most sensitive data an employer holds.
   Access control is server-side and deny-by-default.
4. **Simplicity.** The smallest design that satisfies the requirements — one deployable
   API, one database, one SPA. Complexity must be justified by a requirement, not by
   anticipated future scale.
5. **Testability.** Payroll calculation is pure and callable without HTTP or a database.

Explicit non-goals: horizontal write scalability, sub-100 ms latency, multi-tenancy,
zero-downtime schema evolution. None are required by the specification, and designing
for them now would cost more than adding them later.

## 2. System context

```
                         ┌──────────────────────────────┐
     HR / ADMIN ────────►│                              │
     (browser)           │   ACME Salary Management     │
                         │                              │──────► SMTP relay
     EMPLOYEE ──────────►│   (this system)              │        (optional:
     (browser)           │                              │         payslip email)
                         └──────────────────────────────┘
```

There are no inbound integrations and exactly one optional outbound one. Attendance,
banking, and tax filing are all out of scope ([requirements.md §9](requirements.md#9-out-of-scope)),
which is what keeps the context diagram this small — and is the single biggest reason the
architecture can stay a modular monolith.

## 3. Container view

```
┌─────────────────────────────────────────────────────────────────────────────┐
│ Browser                                                                     │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │ Angular SPA (static bundle: JS/CSS/HTML)                              │  │
│  │  routing · auth guard · HTTP interceptor (bearer token) · feature UIs │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────┬──────────────────────────────────────────┘
                                   │  HTTPS, JSON, Authorization: Bearer <JWT>
                                   ▼
┌─────────────────────────────────────────────────────────────────────────────┐
│ Spring Boot API (single JVM, stateless)                                     │
│  ┌──────────────┐  ┌──────────────┐  ┌───────────────┐  ┌────────────────┐  │
│  │ Security     │  │ Controllers  │  │ Services      │  │ Repositories   │  │
│  │ filter chain │─►│ (DTO in/out) │─►│ (rules, tx)   │─►│ (Spring Data)  │  │
│  └──────────────┘  └──────────────┘  └───────┬───────┘  └────────┬───────┘  │
│                                              │                   │          │
│                              ┌───────────────▼────────┐          │          │
│                              │ payroll calculation    │          │          │
│                              │ (pure, no I/O)         │          │          │
│                              └────────────────────────┘          │          │
└──────────────────────────────────────────────────────────────────┼──────────┘
                                                                   │ JDBC
                                                                   ▼
                                                        ┌────────────────────┐
                                                        │ PostgreSQL         │
                                                        │ (Flyway-migrated)  │
                                                        └────────────────────┘
```

| Container | Responsibility | Scales by |
| --- | --- | --- |
| Angular SPA | Presentation, client-side validation, token handling | CDN / static hosting |
| Spring Boot API | All business rules, authorisation, persistence, PDF rendering | Adding instances (stateless) |
| PostgreSQL | Durable state, uniqueness and referential constraints | Vertically; read replicas later |

The API serves the built SPA bundle in the single-artifact deployment, which removes CORS
and cookie-domain questions entirely. The SPA can also be hosted separately without code
changes, since it only ever talks to `/api/v1`.

## 4. Backend architecture

### 4.1 Layering

Four layers, one direction of dependency. A layer may call the one below it and never the
one above.

```
   HTTP
     │
     ▼
┌──────────────────┐  Controllers      — HTTP mapping, DTO binding, bean validation.
│  web             │                     No business logic, no entities, no transactions.
├──────────────────┤
│  service         │  Services         — business rules, authorisation decisions,
│                  │                     transaction boundaries, audit emission.
├──────────────────┤
│  domain          │  Entities +       — JPA entities, value objects, and the pure
│                  │  calculation        payroll calculator. No Spring, no I/O.
├──────────────────┤
│  repository      │  Repositories     — Spring Data JPA interfaces, queries.
└──────────────────┘
     │
     ▼  JDBC
```

The rule that earns its keep is *transactions begin and end in the service layer*. A
payroll run must be atomic ([NFR-3.1](requirements.md#43-reliability-and-data-integrity));
if controllers could open transactions, atomicity would become a property of the HTTP
layer, which is the wrong place to reason about it.

### 4.2 Package structure

Packages are cut by feature first, layer second. A feature's code sits together, so a
change to salary structures touches one directory rather than four.

```
com.acme.salary
├── config/                 SecurityConfig, OpenApiConfig, JacksonConfig, WebConfig
├── common/
│   ├── error/              ApiError, GlobalExceptionHandler, domain exceptions
│   ├── money/              Money helpers: scale, rounding mode, words conversion
│   └── audit/              AuditEvent, AuditService, @Audited aspect
├── security/               JwtService, JwtAuthFilter, CurrentUser, RoleGuard
├── employee/               EmployeeController · Service · Repository · dto/ · Employee
├── orgdata/                Department, Designation, Grade + controllers/services
├── salarystructure/        SalaryStructure(Component) + assignment service
├── salarycomponent/        SalaryComponent definitions
├── payroll/
│   ├── run/                PayrollRunController · Service · state machine
│   └── calc/               PayrollCalculator, ProrationPolicy, RoundingPolicy  ← pure
├── payslip/                Payslip, PayslipLine, PdfRenderer
└── report/                 Payroll register, department cost queries
```

`payroll/calc` is the architectural centre of the system: it has no Spring annotations,
no repository references, and no clock or database access. It takes a structure, a period,
and attendance figures, and returns computed lines. That purity is what makes the 90%
coverage target in [NFR-5.3](requirements.md#45-maintainability-and-quality) reachable
without a running database.

### 4.3 Request lifecycle

```
Request
  │
  ├─ 1. JwtAuthFilter          validate signature + expiry → populate SecurityContext
  ├─ 2. Method security        @PreAuthorize on the controller or service method
  ├─ 3. Controller             bind + @Valid the request DTO
  ├─ 4. Service (@Transactional)
  │       ├─ load aggregates via repositories
  │       ├─ enforce business invariants  → throw domain exception on violation
  │       ├─ mutate state / call payroll/calc
  │       └─ emit audit event in the same transaction
  ├─ 5. Mapper                 entity → response DTO
  └─ 6. Response               JSON, or ApiError from GlobalExceptionHandler
```

Domain exceptions map to status codes in one place, so a rule can signal a failure without
knowing anything about HTTP:

| Exception | Status | Example |
| --- | --- | --- |
| `ValidationException` / bean validation | 400 | missing `BASIC` component |
| `AuthenticationException` | 401 | expired token |
| `AccessDeniedException` | 403 | employee reading another's payslip |
| `NotFoundException` | 404 | unknown employee id |
| `ConflictException` | 409 | duplicate employee code; second run for a period |
| `IllegalStateTransitionException` | 409 | finalising an already-finalised run |

## 5. The payroll engine

The most consequential part of the system, and the part most worth describing precisely.

### 5.1 Run state machine

```
                 POST /payroll-runs
                         │
                         ▼
                    ┌─────────┐   recompute (idempotent)
                    │  DRAFT  │◄──────────┐
                    └────┬────┘           │
              finalise   │   cancel       │  adjust LOP days
                  ┌──────┴──────┐         └──────────────┐
                  ▼             ▼                        │
           ┌────────────┐  ┌───────────┐                 │
           │ FINALISED  │  │ CANCELLED │                 │
           │ (terminal, │  │ (terminal)│                 │
           │  immutable)│  └───────────┘                 │
           └────────────┘                                │
                  └──────────── no transitions out ──────┘
```

Payslips are computed and **persisted** when the run is created, while it is still
`DRAFT`, so HR reviews exactly the rows that will be published
([FR-5.6](requirements.md#35-payroll-run)). Finalisation does not recompute; it flips the
run's state, stamps `finalisedAt`, and makes the existing payslips visible to employees
([FR-5.8](requirements.md#35-payroll-run)). Visibility is therefore a function of the
parent run's state, not a separate flag — there is no way for a payslip to be visible
while its run is still a draft.

Recompute deletes and regenerates the draft run's payslips inside one transaction. It is
idempotent: the same inputs always produce the same rows.

### 5.2 Calculation pipeline

For one employee in one period:

```
 1. Resolve structure   → the structure with the greatest effectiveFrom ≤ period end,
                          not superseded before the period end
 2. Resolve attendance  → totalDays = days in calendar month
                          paidDays  = totalDays − lopDays
 3. Prorate earnings    → amount × (paidDays / totalDays)      per earning component
 4. Round earnings      → HALF_UP, scale 2                     per component
 5. Gross               → Σ rounded earnings
 6. Deductions          → FLAT              → value as-is
                          PERCENT_OF_BASIC  → prorated basic × pct, then round
 7. Net                 → gross − Σ rounded deductions
 8. Emit payslip        → header totals + one PayslipLine per component
```

Two properties of this ordering matter and are deliberate:

- **Rounding happens per component, before summation.** So the payslip's displayed lines
  always add up to its displayed total ([NFR-3.3](requirements.md#43-reliability-and-data-integrity)).
  Summing unrounded values and rounding once would be marginally more "accurate" in the
  abstract and visibly wrong on paper — a payslip whose column does not add up is a
  support ticket.
- **Percentage deductions use the prorated basic, not the contractual basic**
  ([FR-5.4](requirements.md#35-payroll-run)). Loss of pay reduces the base that
  provident-fund-style deductions are computed on.

### 5.3 Atomicity and performance

One run for 1,000 employees must finish inside 60 seconds
([NFR-1.3](requirements.md#41-performance)) and must leave nothing partial
([FR-5.9](requirements.md#35-payroll-run)). The design:

- **Batch reads.** Employees, their effective structures, and the structures' components
  are loaded in three queries, not three per employee. This is the N+1 problem that would
  otherwise dominate the run's wall clock.
- **Pure in-memory computation.** Step 3–7 above touch no I/O at all.
- **Batched writes.** Payslips and lines are inserted with JDBC batching
  (`hibernate.jdbc.batch_size`), in one transaction.
- **Synchronous execution.** The HTTP request blocks until the run completes. At the
  specified scale this stays well inside the budget, and it avoids a job queue, a polling
  endpoint, and the "run stuck in RUNNING" failure mode. See
  [ADR-011](decisions.md#adr-011-execute-payroll-runs-synchronously).

## 6. Frontend architecture

### 6.1 Structure

```
frontend/src/app/
├── core/
│   ├── auth/           AuthService, auth guard, role guard, token storage
│   ├── http/           auth interceptor, error interceptor, loading interceptor
│   ├── layout/         shell, navigation, role-aware menu
│   └── reference-data/ departments, designations, grades
├── shared/             reusable UI: money display, period picker, data table, empty state
└── features/
    ├── auth/           login, change password
    ├── employees/      list · detail · form
    ├── structures/     structure history, assignment form with live preview
    ├── payroll/        run list, draft review, finalise confirmation
    ├── payslips/       my payslips, all payslips, payslip view + PDF download
    └── reports/        register, department cost, dashboard
```

Built so far: `core/` in full, `shared/` for money, dates, the paging contract and the
empty state, and `features/auth/login` plus `features/employees` (list and detail). The
remaining feature areas are directories in this plan, not yet on disk.

Reference data sits in `core/` rather than under a feature because three screens will
need the same three lists and none of them owns them. It deliberately does **not** cache:
ADR-019 rules out a client store, and the hazard being avoided is showing a stale figure.

The application is **zoneless** and uses signals for component state, which is Angular 21's
default. The practical consequence is that anything the template reads must be a signal or
an input — a plain mutated field will not re-render. `toSignal`/`toObservable` are the
bridge to the RxJS the HTTP client returns.

Feature areas are lazy-loaded by route, so an EMPLOYEE never downloads the payroll or
reporting code. That is a bundle-size decision, not a security one — the security control
is the API's authorisation ([NFR-2.2](requirements.md#42-security)).

### 6.2 State and data flow

State is deliberately thin. There is no global store: server data is fetched per route
through feature services and held in component state, because every screen here is a
straightforward read of server-owned data with no cross-screen shared mutation. The only
genuinely global state is the authenticated user and their role, held in `AuthService`.

Three interceptors carry the cross-cutting client behaviour:

1. **Auth interceptor** — attaches the bearer token; on 401, clears the session and
   redirects to login.
2. **Error interceptor** — maps the `ApiError` envelope to user-facing messages and pushes
   `fieldErrors` back onto the originating form.
3. **Loading interceptor** — drives a global progress indicator.

Their registration order is load-bearing, and it is not the obvious one. A request runs
through the list top to bottom, so the first entry is outermost — but a failure propagates
back the other way, innermost `catchError` first. The auth interceptor must therefore be
registered **after** the error interceptor: it needs to see a raw `HttpErrorResponse` to
recognise a 401, and the error interceptor replaces that with an `ApiFailure` on the way
out. Reversed, an expired token would silently fail to end the session. The registration
is `[loading, error, auth]`, and `auth.interceptor.spec.ts` registers them the same way so
the test cannot pass while the application is broken.

Two narrower rules matter for the same reason:

- The auth interceptor attaches nothing to `/auth/login` or `/auth/refresh`. A user whose
  session expired still holds a token, and sending it to the endpoint that would fix the
  problem is pointless at best.
- A 401 only ends the session when the failed request actually carried a token. A 401 from
  the login endpoint is a wrong password; treating it as an expiry would navigate away
  from the form and discard what the user typed.

### 6.3 Money and locale on the client

Amounts arrive as strings, not JSON numbers, and are never parsed into JavaScript
`number` for arithmetic — IEEE-754 doubles cannot represent decimal currency exactly.
The client formats and displays; it does not calculate. The one exception is the salary
structure preview ([FR-4.5](requirements.md#34-salary-structure)), which shows an
*indicative* gross/net as the form is edited; the authoritative figures come from the
server on save.

`shared/money.ts` takes this literally: thousands separators are inserted into the digits
of the string, so no `Number` is ever constructed and the characters displayed are the
characters the server sent. It is more code than `Intl.NumberFormat` would be, and that is
the cost of the rule rather than an argument against it.

Calendar dates get the same treatment in `shared/dates.ts`, for a sharper reason. A
`LocalDate` arrives as `"2022-06-01"`, and per the ECMAScript spec `new Date("2022-06-01")`
is parsed as *UTC* midnight — which renders as 31 May in any timezone behind UTC. Angular's
`DatePipe` would therefore show every joining and exit date a day early for users west of
Greenwich, and those dates drive payroll eligibility and proration. The dates are formatted
by splitting the string, so no `Date` is involved and no zone can shift them.

## 7. Data architecture

### 7.1 Shape

The full entity list is in [requirements.md §5](requirements.md#5-data-model). What
matters architecturally is that the schema encodes three different notions of time:

| Pattern | Where | Why |
| --- | --- | --- |
| **Effective-dated** | `SalaryStructure.effectiveFrom` / `supersededOn` | A raise applies from a date; payroll for an earlier period must still see the old figures. |
| **Period-stamped** | `PayrollRun.periodMonth` / `periodYear` | A run belongs to a calendar month, independent of when it was executed. |
| **Append-only** | `AuditEvent.occurredAt` | History must never be rewritten. |

Employee records are soft-deleted (status + `exitDate`) rather than removed, because a
payslip from three years ago must still resolve its employee
([FR-2.5](requirements.md#32-employee-management)).

### 7.2 Integrity, in the database and not only in Java

Invariants that must never be violated are enforced by constraints, so a race between two
requests cannot break them:

- `UNIQUE (employee_code)`, `UNIQUE (work_email)` on employees
- A **partial** unique index on payroll runs over `(period_year, period_month)`
  `WHERE status IN ('DRAFT','FINALISED')` — the structural guard behind
  [FR-5.7](requirements.md#35-payroll-run). It has to be partial: a plain unique
  constraint would also block the legitimate retry after a run is cancelled. The
  service's pre-check is a nicety for a clean 409; the index is what actually prevents a
  double run
- A partial unique index on `salary_structures (employee_id) WHERE superseded_on IS NULL`
  — an employee has at most one open revision ([FR-4.4](requirements.md#34-salary-structure))
- `UNIQUE (payroll_run_id, employee_id)` on payslips
- `NUMERIC(12,2)` on every monetary column, with `CHECK (amount >= 0)` where a negative
  value is meaningless
- Foreign keys on every relationship, with `ON DELETE RESTRICT` — reference data in use
  cannot be deleted ([FR-3.3](requirements.md#33-reference-data))

### 7.3 Migrations

Flyway, forward-only, under `backend/src/main/resources/db/migration`, named
`V{n}__{description}.sql`. `spring.jpa.hibernate.ddl-auto` is `validate` in every profile
including `dev`, so a drift between entities and schema fails at startup rather than in
production. Applied migrations are never edited. Dev seed data lives in a separate
`dev`-profile-only migration, keeping test fixtures out of the production schema path.

## 8. Security architecture

### 8.1 Layers of defence

```
 TLS (transport)
   └─ JwtAuthFilter          is the caller authenticated?
        └─ @PreAuthorize     does the caller's role permit this operation?
             └─ Service      is this caller permitted this *record*?  (ownership)
                  └─ Database  constraints + least-privilege DB user
```

The third layer is the one most often missed. Role checks answer "may an EMPLOYEE read
payslips?" but not "may *this* employee read *this* payslip". Ownership is therefore
checked in the service against the authenticated principal
([FR-1.5](requirements.md#31-authentication-and-authorisation)), and endpoints that serve
an employee's own data are also exposed under `/me` paths so the common case has no id in
the URL to tamper with.

### 8.2 Tokens

Stateless JWTs, HMAC-signed, 60-minute access tokens plus 7-day refresh tokens
([FR-1.7](requirements.md#31-authentication-and-authorisation)). The cost of statelessness
is honest and worth stating: **an access token cannot be revoked before it expires.** A
deactivated employee may therefore retain API access for up to an hour. Mitigations are a
short access-token lifetime, refresh-token revocation on deactivation, and a per-user
`tokenVersion` claim that invalidates outstanding tokens on password change. See
[ADR-004](decisions.md#adr-004-stateless-jwt-authentication-instead-of-server-side-sessions).

### 8.3 Data protection

Passwords are BCrypt hashes and never serialised. Logs carry a correlation id but never
tokens, passwords, or salary figures
([NFR-6.2](requirements.md#46-observability)) — which means the usual "log the request
body on error" habit is prohibited here. Error responses expose no stack traces or SQL
([NFR-2.6](requirements.md#42-security)). Payslip PDFs are streamed through the same
authorisation path as the JSON payslip; there are no unauthenticated download URLs.

## 9. Cross-cutting concerns

| Concern | Mechanism |
| --- | --- |
| **Validation** | Bean Validation on request DTOs for shape; services for rules that need database state. Both surface through the same `ApiError.fieldErrors`. |
| **Error handling** | One `@RestControllerAdvice`. Controllers contain no try/catch. |
| **Auditing** | Emitted inside the business transaction, so an audit record cannot survive a rolled-back change or vice versa. See [ADR-013](decisions.md#adr-013-application-level-audit-events-instead-of-hibernate-envers). |
| **Transactions** | `@Transactional` on service methods only; read-only flag on queries. |
| **Pagination** | `Pageable` at the controller, honoured in the query, capped at 100 ([NFR-1.2](requirements.md#41-performance)). Never `findAll()` then slice. |
| **Time** | An injected `Clock`, never `LocalDate.now()` inline — period boundaries and effective dates must be testable. |
| **Money** | `BigDecimal`, scale 2, `HALF_UP`, centralised in `common/money`. No `double` anywhere in the codebase. |
| **API docs** | springdoc generates OpenAPI from the controllers and DTOs, so the published contract cannot drift from the code. |
| **Observability** | `/actuator/health` for probes; structured logs with a request correlation id. |

## 10. Deployment

```
┌──────────────────────────── Docker Compose / container host ────────────────┐
│                                                                             │
│   ┌────────────────────────────┐        ┌──────────────────────────────┐    │
│   │ api                        │        │ db                           │    │
│   │ acme-salary-api:<tag>      │───────►│ postgres:15                  │    │
│   │ serves /api/v1 + SPA       │        │ volume: pgdata               │    │
│   │ :8080                      │        │ :5432                        │    │
│   └────────────────────────────┘        └──────────────────────────────┘    │
│              ▲                                                              │
└──────────────┼──────────────────────────────────────────────────────────────┘
               │ TLS terminated upstream (reverse proxy / platform ingress)
          end users
```

Configuration is entirely environment-driven ([NFR-2.5](requirements.md#42-security)); the
same image runs in every environment with a different `SPRING_PROFILES_ACTIVE` and
different secrets. The container runs as a non-root user, and the image is a JRE-slim base
with only the fat jar on top. Because the API is stateless, running two instances behind
the proxy requires no session affinity — the only shared state is PostgreSQL.

CI runs build, test, and lint on every push and blocks merge on failure
([NFR-5.5](requirements.md#45-maintainability-and-quality)).

## 11. Testing strategy

| Level | Scope | Tooling | Where the emphasis goes |
| --- | --- | --- | --- |
| **Unit** | `payroll/calc`, money helpers, state machine | JUnit 5, no Spring context | Heaviest. Proration edge cases, rounding, zero and full LOP, mid-month structure changes. |
| **Service** | Business rules with mocked repositories | JUnit 5 + Mockito | Invariants: immutability, eligibility, ownership. |
| **Integration** | Controller → database | `@SpringBootTest` + Testcontainers PostgreSQL | Migrations, constraints, authorisation, the `ApiError` contract. |
| **Frontend unit** | Services, guards, interceptors, pure components | Jasmine/Karma | Token handling, error mapping, role-based rendering. |
| **Acceptance** | The six scenarios in [requirements.md §7](requirements.md#7-acceptance-criteria) | Integration tests | The definition of done. |

Integration tests run against real PostgreSQL via Testcontainers rather than H2. An
in-memory database that does not enforce the same constraints would let exactly the bugs
this design relies on the database to prevent slip through
([ADR-012](decisions.md#adr-012-testcontainers-postgresql-for-integration-tests-instead-of-h2)).

## 12. Scalability and evolution

The design is intentionally sized for the stated load. The paths out, if load or scope
changes, and the trigger for each:

| Pressure | Response | Trigger to act |
| --- | --- | --- |
| More concurrent users | Run more API instances; it is already stateless | p95 read latency approaching 500 ms |
| Payroll run exceeds the time budget | Move to an async job with a status endpoint, or batch by department | A run above ~30 s, or headcount past ~5,000 |
| Report queries slow the transactional workload | Read replica, or materialised monthly aggregates | Register queries above ~2 s |
| Real tax rules arrive | Replace `calculationType` with a formula/rules engine behind the existing calculator interface | First requirement that needs slabs |
| Multi-company | Add a tenant column and a tenant filter — a schema and query change, not a rewrite | A second legal entity |
| Team grows past one squad | Extract `payroll` into its own service; the package boundary is already the seam | Independent deploy cadence needed |

The modular monolith is what makes each of these a contained change. Feature packages with
no cross-feature entity references mean the extraction seams already exist, without paying
for distribution today ([ADR-001](decisions.md#adr-001-modular-monolith-instead-of-microservices)).

## 13. Known weaknesses

Stated plainly, because an architecture document that lists only strengths is a sales
document:

1. **Access tokens cannot be revoked instantly.** Up to 60 minutes of residual access
   after deactivation (§8.2).
2. **The payroll run is a synchronous HTTP request.** A client timeout or dropped
   connection leaves the user without a result even though the transaction may have
   committed. Re-fetching the run reveals the truth, but the UX is poor, and the design
   does not scale past a few thousand employees (§5.3).
3. **Single database instance, single point of failure.** Availability rests on the
   database's backups and the host. No requirement asks for more.
4. **No end-to-end browser test suite.** Acceptance scenarios are verified at the API
   level, so a defect purely in the Angular layer could pass CI.
5. **PDF rendering happens in the API process.** A large batch of concurrent PDF downloads
   competes for the same heap and CPU as payroll computation.
6. **Correction of a finalised run is not supported at all.** The only remedy is a
   deliberate design constraint ([FR-6.6](requirements.md#36-payslips)) — in a real
   payroll department, an off-cycle adjustment mechanism would eventually be mandatory.
