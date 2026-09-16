# ACME Salary Management — Architecture Decisions

**Version:** 0.1 (draft)
**Status:** Proposed — decisions taken up front, before implementation
**Last updated:** 2026-09-16

This is the decision log. [requirements.md](requirements.md) says what the system must do;
[architecture.md](architecture.md) says how it is built; this file says **why**, and —
more usefully — **what each choice cost**. Every entry names the alternatives that were
rejected and the conditions under which the decision should be revisited.

Decision records are immutable once accepted. A change of mind is a new record that
supersedes an old one, not an edit.

---

## Index

| ID | Decision | Status |
| --- | --- | --- |
| [ADR-001](#adr-001-modular-monolith-instead-of-microservices) | Modular monolith instead of microservices | Accepted |
| [ADR-002](#adr-002-java-17--spring-boot-3x-for-the-backend) | Java 17 + Spring Boot 3.x for the backend | Accepted |
| [ADR-003](#adr-003-angular-for-the-frontend) | Angular for the frontend | Accepted |
| [ADR-004](#adr-004-stateless-jwt-authentication-instead-of-server-side-sessions) | Stateless JWT authentication instead of server-side sessions | Accepted |
| [ADR-005](#adr-005-postgresql-as-the-only-datastore) | PostgreSQL as the only datastore | Accepted |
| [ADR-006](#adr-006-bigdecimal-with-per-component-half_up-rounding) | `BigDecimal` with per-component HALF_UP rounding | Accepted |
| [ADR-007](#adr-007-flyway-migrations-with-ddl-autovalidate-everywhere) | Flyway migrations with `ddl-auto=validate` everywhere | Accepted |
| [ADR-008](#adr-008-dtos-at-the-api-boundary-entities-never-serialised) | DTOs at the API boundary, entities never serialised | Accepted |
| [ADR-009](#adr-009-effective-dated-append-only-salary-structures) | Effective-dated, append-only salary structures | Accepted |
| [ADR-010](#adr-010-draftfinalise-state-machine-with-immutable-finalised-runs) | Draft/finalise state machine with immutable finalised runs | Accepted |
| [ADR-011](#adr-011-execute-payroll-runs-synchronously) | Execute payroll runs synchronously | Accepted |
| [ADR-012](#adr-012-testcontainers-postgresql-for-integration-tests-instead-of-h2) | Testcontainers PostgreSQL for integration tests instead of H2 | Accepted |
| [ADR-013](#adr-013-application-level-audit-events-instead-of-hibernate-envers) | Application-level audit events instead of Hibernate Envers | Accepted |
| [ADR-014](#adr-014-soft-delete-employees-instead-of-hard-delete) | Soft-delete employees instead of hard delete | Accepted |
| [ADR-015](#adr-015-three-flat-roles-instead-of-granular-permissions) | Three flat roles instead of granular permissions | Accepted |
| [ADR-016](#adr-016-two-calculation-types-instead-of-a-formula-engine) | Two calculation types instead of a formula engine | Accepted |
| [ADR-017](#adr-017-render-payslip-pdfs-on-demand-instead-of-storing-them) | Render payslip PDFs on demand instead of storing them | Accepted |
| [ADR-018](#adr-018-serve-the-spa-from-the-api-as-a-single-artifact) | Serve the SPA from the API as a single artifact | Accepted |
| [ADR-019](#adr-019-no-global-client-state-store) | No global client state store | Accepted |
| [ADR-020](#adr-020-single-currency-and-single-legal-entity) | Single currency and single legal entity | Accepted |
| [ADR-021](#adr-021-aggregate-compensation-analytics-in-the-application-not-in-sql) | Aggregate compensation analytics in the application, not in SQL | Accepted |

A consolidated view of what all of this bought and gave up is in
[Tradeoff summary](#tradeoff-summary) at the end.

---

## ADR-001: Modular monolith instead of microservices

**Status:** Accepted

**Context.** The system has five coherent feature areas (employees, reference data, salary
structures, payroll, payslips) and one consumer. The most important operation — a payroll
run — reads employees, structures, and components and writes payslips in a single atomic
unit ([FR-5.9](requirements.md#35-payroll-run)). Team size is one squad.

**Decision.** One deployable Spring Boot application, internally partitioned into feature
packages with no cross-feature entity references; extraction seams left at the package
boundaries.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Microservices per feature area | The payroll run's atomicity would become a distributed transaction or a saga. That is a large amount of machinery bought to solve a problem this system does not have. |
| Serverless functions | Cold starts against a 60-second batch job, plus no natural home for a single long transaction. |
| Unstructured monolith (layer-only packages) | Cheaper today, but no seams — the later extraction in [architecture.md §12](architecture.md#12-scalability-and-evolution) becomes a rewrite. |

**Consequences.**

- *Gained:* a single transaction for the payroll run; one thing to deploy, test, and debug; no network hop or eventual-consistency window inside a business operation.
- *Cost:* the whole application scales as one unit — the PDF renderer and the payroll engine share a heap. A single bad deploy takes down everything. Module boundaries are conventions enforced by review, not by the compiler, so they can erode.
- *Mitigation:* feature-first packages and a standing review rule that a service may not import another feature's entities, only its service interface.

**Revisit when** two teams need independent deploy cadence, or payroll's resource profile
starts starving request-serving.

---

## ADR-002: Java 17 + Spring Boot 3.x for the backend

**Status:** Accepted

**Context.** The domain is transactional, relational, and rule-heavy, with strict money
handling and non-negotiable authorisation. It is also a team-and-organisation choice as
much as a technical one.

**Decision.** Java 17 LTS with Spring Boot 3.x (Web, Validation, Security, Data JPA).

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Node.js + Express/NestJS | JavaScript has no native decimal type; money handling would depend on a library convention rather than a language guarantee. Weaker transaction ergonomics for this workload. |
| .NET + EF Core | Technically an excellent fit (`decimal` is native and first-class). Rejected on team familiarity and the surrounding JVM ecosystem, not on merit. |
| Kotlin on Spring Boot | Genuinely better language ergonomics, but narrows the pool of reviewers and maintainers for a project meant to be read by others. |

**Consequences.**

- *Gained:* `BigDecimal` and declarative transactions in the platform; method-level security; Bean Validation; a mature migration and test ecosystem.
- *Cost:* verbosity — DTOs, mappers, and builders are a real fraction of the code. Slower startup than the alternatives, which is felt in the test suite.

**Revisit when** never, realistically, within this project's life. The cost of a platform
change would exceed the benefit.

---

## ADR-003: Angular for the frontend

**Status:** Accepted

**Context.** The UI is form-dense and table-dense: employee CRUD, structure assignment with
a live preview, payroll review grids, payslip views. Routing, validation, and HTTP
interception are needed on day one.

**Decision.** Angular 17+ with TypeScript, lazy-loaded feature modules, and HTTP
interceptors for auth and errors.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| React + Vite | Equally capable, and a lighter start. Rejected because every piece of structure here — routing, forms, HTTP, DI — would be an assembled choice rather than a given, and consistency matters more than flexibility on a form-heavy admin UI. |
| Server-rendered Thymeleaf | Simplest possible stack and no separate build. Rejected because the live structure preview and draft-run review screens are interactive enough that full page reloads would be a visible regression. |
| Vue | No decisive advantage over Angular for this UI; same reasoning as React with a smaller local talent pool. |

**Consequences.**

- *Gained:* strongly typed reactive forms with validation that mirrors the server's; one obvious place for token attachment and error mapping; a conventional structure a new contributor can navigate.
- *Cost:* a heavier initial bundle and a steeper learning curve than React; more ceremony for simple screens.

**Revisit when** not applicable for this project; the SPA talks only to `/api/v1`, so a
replacement would not touch the backend.

---

## ADR-004: Stateless JWT authentication instead of server-side sessions

**Status:** Accepted

**Context.** [FR-1.1](requirements.md#31-authentication-and-authorisation) requires token
auth and [architecture.md §3](architecture.md#3-container-view) requires the API to be
horizontally scalable without session affinity.

**Decision.** HMAC-signed JWT access tokens with a 60-minute lifetime, plus 7-day refresh
tokens. No server-side session store. A `tokenVersion` claim, bumped on password change and
deactivation, invalidates outstanding tokens.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Server-side sessions (`HttpSession`) | Instant revocation, which is genuinely better security. Rejected because it requires sticky sessions or a shared session store to scale, and reintroduces CSRF as a concern. |
| Opaque tokens checked against the database per request | Instant revocation without CSRF, at the cost of a database round trip on every single request — the thing statelessness was chosen to avoid. |
| Long-lived tokens, no refresh | Simpler, and strictly worse: a leaked token stays valid for its whole life. |

**Consequences.**

- *Gained:* any instance can serve any request; no session replication; no CSRF surface for a bearer-token API.
- *Cost — the significant one:* **an access token cannot be revoked before it expires.** A deactivated employee may keep API access for up to 60 minutes. This is a deliberate, documented weakness ([architecture.md §13](architecture.md#13-known-weaknesses)), not an oversight.
- *Mitigation:* short access-token lifetime; refresh tokens revoked on deactivation; `tokenVersion` for the password-change case.

**Revisit when** a requirement demands immediate revocation — at which point the opaque-token
alternative, or a short-TTL cache of revoked ids, becomes the right trade.

*Resolves [requirements.md open question 2](requirements.md#10-open-questions): refresh
tokens are in scope.*

---

## ADR-005: PostgreSQL as the only datastore

**Status:** Accepted

**Context.** The data is highly relational, the invariants are uniqueness and referential
constraints, and money requires exact decimal storage.

**Decision.** PostgreSQL 15+ as the single source of truth. No cache, no search index, no
document store.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| MySQL | Would work. PostgreSQL chosen for stricter default behaviour, better `NUMERIC` semantics, and richer constraint support. |
| MongoDB | No multi-document constraints of the kind this schema leans on, and no exact decimal by default. The data is relational; storing it as documents would move integrity into application code. |
| Adding Redis for caching | No measured need. A cache introduces invalidation bugs, and stale salary data is a uniquely bad thing to serve. |

**Consequences.**

- *Gained:* `NUMERIC(12,2)` exactness; unique and foreign-key constraints that hold under concurrency ([architecture.md §7.2](architecture.md#72-integrity-in-the-database-and-not-only-in-java)); one backup and restore story.
- *Cost:* a single point of failure, and reporting queries compete with transactional traffic on the same instance.

**Revisit when** register/report queries exceed ~2 s — add a read replica before adding a
cache.

---

## ADR-006: `BigDecimal` with per-component HALF_UP rounding

**Status:** Accepted

**Context.** The system's first architectural goal is correctness of money. Payslips are
read by employees, who will add the column up by hand.

**Decision.** All monetary values are `BigDecimal` in Java and `NUMERIC(12,2)` in
PostgreSQL. Rounding is HALF_UP to two decimals, applied **per component before
summation**. Amounts cross the API as JSON strings, and the client never does arithmetic on
them.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| `double` / `float` | Cannot represent decimal currency exactly. Non-negotiable. |
| Integer minor units (paise as `long`) | Exact and fast, and a legitimate choice. Rejected because every boundary — JSON, JPA, reports, PDFs — needs a scaling conversion, and each conversion is a place to get the factor wrong. |
| Round once at the end, after summing unrounded values | Marginally more faithful to the arithmetic, and visibly wrong on paper: the printed lines would not sum to the printed total. Rejected in favour of what a reader can verify ([NFR-3.3](requirements.md#43-reliability-and-data-integrity)). |
| Banker's rounding (HALF_EVEN) | Statistically fairer over many operations, but surprising to a payroll operator checking one payslip, and not what local payroll convention expects. |

**Consequences.**

- *Gained:* payslips whose columns add up; reproducible figures; no floating-point drift across recomputation.
- *Cost:* `BigDecimal` is verbose and easy to misuse (`equals` compares scale; `compareTo` is required). Sub-paise fractions are lost per component, so the payslip total can differ by a few paise from the mathematically exact value — accepted, and preferred to a total that does not match its lines.
- *Enforcement:* rounding lives only in `common/money`; a review rule forbids `double` anywhere in the codebase.

---

## ADR-007: Flyway migrations with `ddl-auto=validate` everywhere

**Status:** Accepted

**Context.** Schema constraints are load-bearing for correctness (ADR-005), so the schema
must be explicit, reviewed, and identical across environments.

**Decision.** Forward-only Flyway migrations as the only way the schema changes.
`spring.jpa.hibernate.ddl-auto=validate` in **every** profile, including `dev`. Dev seed
data in a separate `dev`-only migration.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| `ddl-auto=update` in dev, migrations in production | The common compromise, and the source of the classic failure: the schema developers work against silently diverges from the one migrations produce. |
| Liquibase | Equivalent capability; Flyway's plain-SQL files are easier to review in a pull request. |
| Entity-generated schema everywhere | Gives up `CHECK` constraints, precise index control, and any review of destructive changes. |

**Consequences.**

- *Gained:* schema drift fails at startup, in the noisiest possible way; every constraint is visible in a reviewable SQL file; production and dev schemas are provably the same.
- *Cost:* every entity change needs a hand-written migration — friction on exactly the days when iteration should be fast. Mistakes are fixed by a new migration, never an edit, so the history accumulates corrections.

---

## ADR-008: DTOs at the API boundary, entities never serialised

**Status:** Accepted

**Context.** Entities carry fields that must never reach a client (`passwordHash`) and lazy
associations that serialise into either an error or an accidental full-graph query.

**Decision.** Request and response DTOs per endpoint. JPA entities never appear in a
controller signature. Explicit mappers between the two.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Serialise entities with `@JsonIgnore` on sensitive fields | Opt-out security: a new sensitive field is exposed by default until someone remembers to annotate it. Also couples the public contract to the schema. |
| Projection interfaces only | Good for reads, insufficient for writes and for validation messages. |
| MapStruct-generated mappers | Reasonable; deferred to keep the build simple and mapping explicit. Worth adopting if mapper volume becomes tedious. |

**Consequences.**

- *Gained:* opt-in exposure — a field is public only if a DTO names it; a schema change does not silently break clients; validation annotations live on the DTO, where the input actually arrives.
- *Cost:* real boilerplate, and two shapes to keep in step. Deliberate: leaking a `passwordHash` once costs more than all the mapper code.

---

## ADR-009: Effective-dated, append-only salary structures

**Status:** Accepted

**Context.** A raise applies from a date. A payroll run for an earlier period must still
compute against the figures that were in force then
([FR-4.4](requirements.md#34-salary-structure)), and compensation history must be
auditable.

**Decision.** A new structure supersedes the previous one via `effectiveFrom` /
`supersededOn`. Structures are never updated in place, and a structure referenced by a
finalised run can never change at all
([FR-4.7](requirements.md#34-salary-structure)).

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Mutable current structure, no history | Simplest, and destroys the audit trail and the ability to re-run a past period. Fails the second architectural goal outright. |
| Full bi-temporal model (valid time + transaction time) | Correctly models retroactive corrections, and roughly doubles query complexity for a system with no retroactive-correction requirement. |
| Snapshot the structure into each payslip only | Payslips would be reproducible, but there would be no way to answer "what is this employee's package today?" without replaying runs. |

**Consequences.**

- *Gained:* re-running any period gives the same answer; the full revision history is queryable; "who approved this raise, and when" is answerable.
- *Cost:* every read of "the current structure" is a date-predicated query, not a lookup by id — easy to get subtly wrong, and a correctness risk concentrated in one resolution step. That step is therefore a single, heavily tested method ([architecture.md §5.2](architecture.md#52-calculation-pipeline)). Structure rows accumulate; no requirement asks for them to be pruned.

---

## ADR-010: Draft/finalise state machine with immutable finalised runs

**Status:** Accepted

**Context.** A payroll run is consequential and error-prone: an LOP figure entered wrongly
must be caught before employees see it. Once employees have seen it, the record must not
change under them.

**Decision.** `DRAFT → FINALISED` or `DRAFT → CANCELLED`, both terminal. Payslips are
computed and persisted at run creation, while `DRAFT`, so HR reviews exactly the rows that
will be published; finalisation flips state and grants employee visibility without
recomputing. Payslip visibility derives from the parent run's state rather than a separate
flag.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Compute payslips only at finalisation | HR would approve a preview computed by different code than the published rows — the classic preview/reality divergence. |
| Editable finalised payslips | Trivially convenient and indefensible: a published payslip that can be silently altered is not an auditable record. |
| A reversal/adjustment ledger for corrections | The right long-term answer for real payroll. Out of scope ([requirements.md §9](requirements.md#9-out-of-scope)), and noted as a known weakness. |
| A separate `payslip.published` boolean | Two sources of truth for one fact, and a state where a run is a draft but its payslips are visible. |

**Consequences.**

- *Gained:* a review gate before anything reaches an employee; a published payslip is a fixed record; recompute is idempotent, so reviewing and adjusting is safe to repeat.
- *Cost:* **there is no correction path after finalisation at all.** A mistake caught later can only be addressed outside the system. This is the sharpest deliberate limitation in the design.

---

## ADR-011: Execute payroll runs synchronously

**Status:** Accepted

**Context.** A run for 1,000 employees must finish within 60 seconds
([NFR-1.3](requirements.md#41-performance)) and must be atomic
([NFR-3.1](requirements.md#43-reliability-and-data-integrity)).

**Decision.** `POST /payroll-runs` computes the run inside the request, in one transaction,
and returns the draft. No queue, no worker, no polling.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Async job + status polling endpoint | The scalable answer, and it adds a job store, a worker lifecycle, a status endpoint, client polling, and a new failure mode — a run stuck in `RUNNING` after a worker dies. Unjustified at 1,000 employees where batched reads and writes land the operation in seconds. |
| Spring `@Async` without a job store | Asynchrony with none of the durability that makes asynchrony safe: a restart loses the run silently. Worst of both. |
| Per-employee transactions | Faster to recover from a mid-run failure, at the cost of atomicity — a partially paid month is exactly the outcome [FR-5.9](requirements.md#35-payroll-run) forbids. |

**Consequences.**

- *Gained:* a genuinely atomic run; no orchestration to build or operate; a straightforward "click, wait, review" flow.
- *Cost:* an HTTP request held open for seconds, needing raised client and proxy timeouts. A dropped connection leaves the user without a result even though the transaction may have committed — re-fetching the run reveals the truth, but the UX is poor. The design has a hard ceiling at a few thousand employees.

**Revisit when** a run exceeds ~30 seconds, or headcount passes ~5,000 — then move to the
async job, keeping the calculator untouched.

---

## ADR-012: Testcontainers PostgreSQL for integration tests instead of H2

**Status:** Accepted

**Context.** Correctness leans on database constraints (ADR-005, ADR-007) and on Flyway
migrations being valid.

**Decision.** Integration tests run against real PostgreSQL in a container.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| H2 in PostgreSQL compatibility mode | Fast and zero-setup, and it does not reproduce PostgreSQL's `NUMERIC` behaviour, constraint semantics, or SQL dialect. It would pass tests for precisely the bugs this design relies on the database to catch. |
| Shared developer database | Order-dependent, flaky, and impossible to run in parallel or in CI reliably. |
| Mock repositories only, no database tests | Leaves migrations, constraints, and query correctness entirely unverified. |

**Consequences.**

- *Gained:* migrations are exercised on every CI run; constraint violations surface as tests, not incidents; the test database behaves exactly like production.
- *Cost:* Docker becomes a hard prerequisite for the test suite, and container startup adds seconds to every run. Mitigated by reusing a single container across the test class hierarchy.

---

## ADR-013: Application-level audit events instead of Hibernate Envers

**Status:** Accepted

**Context.** [FR-8.1](requirements.md#38-auditing) requires actor, timestamp, entity, and
action for business-meaningful changes — not a field-level diff of every table.

**Decision.** An `AuditEvent` row written by the service layer inside the same transaction
as the change it describes.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Hibernate Envers | Automatic and thorough, at the wrong granularity: it records entity revisions, not business actions ("finalised the March run"). It also doubles the table count and has no natural notion of the acting user. |
| Database triggers | Cannot see the authenticated principal, and hide behaviour from the code the team reads. |
| Log-file auditing only | Not queryable per [FR-8.2](requirements.md#38-auditing), and logs are the wrong durability class for a compliance record. |

**Consequences.**

- *Gained:* audit entries describe business intent; the actor is always known; an audit record cannot survive a rolled-back change, nor a change escape its audit record, because both share one transaction.
- *Cost:* auditing is a manual call at each site — a new mutating operation can simply forget it. Partly addressed with an `@Audited` aspect for the routine cases, and by making the omission a review checklist item.

---

## ADR-014: Soft-delete employees instead of hard delete

**Status:** Accepted

**Context.** A payslip from three years ago must still resolve its employee, and
[FR-2.5](requirements.md#32-employee-management) forbids destroying records.

**Decision.** Deactivation sets `status = INACTIVE` and records an `exitDate`. No delete
endpoint exists. Payroll eligibility is derived from status and exit date
([FR-2.6](requirements.md#32-employee-management)).

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Hard delete | Orphans historical payslips and erases the audit trail. |
| Archive table | Preserves history but splits every query in two, and complicates re-hiring. |

**Consequences.**

- *Gained:* historical payslips stay intact and attributable; re-hiring is a status change; nothing is ever unrecoverable.
- *Cost:* **every** employee query must filter on status — an omission silently includes leavers in a payroll run or a headcount. Contained by routing all employee reads through repository methods that carry the predicate, rather than ad-hoc queries.

---

## ADR-015: Three flat roles instead of granular permissions

**Status:** Accepted

**Context.** [requirements.md §2.2](requirements.md#22-user-classes) defines exactly three
user classes, with capability sets that do not overlap partially.

**Decision.** `ADMIN`, `HR`, `EMPLOYEE` as a single role per user, enforced with
`@PreAuthorize`, plus explicit ownership checks in services for record-level access.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Permission-based model (role → permissions → endpoints) | Flexible and appropriate when roles vary by customer. Here it would add a permission table, a seeding problem, and an indirection with no requirement asking for it. |
| Attribute-based access control | Considerable machinery for three roles. |
| Roles only, no ownership checks | Would satisfy role checks while failing [FR-1.5](requirements.md#31-authentication-and-authorisation) — role answers "may employees read payslips", never "may *this* employee read *this* payslip". |

**Consequences.**

- *Gained:* authorisation is readable at each endpoint and easy to test exhaustively; the role matrix in the README is the whole truth.
- *Cost:* a new capability that cuts across roles (say, a read-only auditor) needs a code change, not configuration. Accepted: three roles are a stated requirement, not a guess.

---

## ADR-016: Two calculation types instead of a formula engine

**Status:** Accepted

**Context.** [FR-3.4](requirements.md#33-reference-data) defines components as `FLAT` or
`PERCENT_OF_BASIC`. Real statutory rules — slabs, caps, conditional eligibility — are
explicitly out of scope.

**Decision.** A closed enum of two calculation types, evaluated by `payroll/calc` behind an
interface that a richer evaluator could later implement.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Expression language (SpEL, MVEL) stored per component | Open-ended power, with user-authored expressions executing in the payroll process — an injection surface and an untestable calculation path, for flexibility no requirement asks for. |
| Rules engine (Drools) | Heavyweight; the same objection plus an operational dependency. |
| Hard-coded component list | Marginally simpler still, and removes the configurability [FR-3.4](requirements.md#33-reference-data) asks for. |

**Consequences.**

- *Gained:* every possible calculation is enumerable and unit-testable; no user input is ever evaluated as code; the calculator stays pure and fast.
- *Cost:* genuinely new calculation shapes — a cap, a slab, percent-of-gross — need code and a migration, not configuration. The interface seam means that change is additive rather than structural.

**Revisit when** the first requirement lands that needs slab-based or capped computation.

*Relates to [requirements.md open question 3](requirements.md#10-open-questions): no
Basic-to-CTC ratio is enforced; the grade CTC band in
[FR-4.3](requirements.md#34-salary-structure) is the only compensation guard.*

---

## ADR-017: Render payslip PDFs on demand instead of storing them

**Status:** Accepted

**Context.** [FR-6.4](requirements.md#36-payslips) requires PDF download. Payslip data is
immutable once finalised (ADR-010), so a rendering is reproducible from the stored rows at
any time.

**Decision.** Render on request, stream through the same authorisation path as the JSON
payslip. No blob storage, no stored files.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Generate and store at finalisation | Faster downloads and a byte-exact archive — valuable if the document itself were the legal record. It also adds object storage or a database blob column, a backup burden, and a finalisation step that can fail after the data is committed. |
| Pre-signed external storage URLs | Fast and cheap, and it creates a download path that bypasses application authorisation for the most sensitive document in the system. |

**Consequences.**

- *Gained:* one source of truth (the payslip rows); no storage to secure, back up, or reconcile; a template fix retroactively improves every historical payslip.
- *Cost:* CPU on every download, in the same process as payroll computation ([architecture.md §13](architecture.md#13-known-weaknesses)); a template change alters how an old payslip *looks*, which would be unacceptable if the PDF were the record of truth rather than a rendering of it.

*Resolves [requirements.md open question 1](requirements.md#10-open-questions).*

---

## ADR-018: Serve the SPA from the API as a single artifact

**Status:** Accepted

**Context.** Two containers and two origins would mean CORS configuration, a second
deployment target, and version skew between client and API.

**Decision.** The Angular bundle is packaged into the Spring Boot jar and served as static
content, with unmatched non-`/api` paths forwarded to `index.html` for client-side routing.
The SPA calls only relative `/api/v1` paths, so it can still be hosted separately without
code changes.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Separate static host / CDN | Better caching and independent frontend deploys; costs a CORS policy, a second pipeline, and the possibility of a frontend running against an incompatible API version. |
| Reverse proxy joining two containers at one origin | Removes CORS but keeps two deploy targets and adds proxy configuration. |

**Consequences.**

- *Gained:* one artifact, one version, one origin; no CORS; local development needs only a dev-server proxy.
- *Cost:* a frontend-only change requires rebuilding and redeploying the backend, and static assets are served by the JVM rather than a CDN.

---

## ADR-019: No global client state store

**Status:** Accepted

**Context.** Every screen is a read of server-owned data. There is no client-side domain
model shared across features and mutated in several places.

**Decision.** Feature services fetch per route; components hold their own view state. Global
state is limited to the authenticated user and role in `AuthService`.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| NgRx | A well-understood pattern with real boilerplate, justified by shared mutable client state — which this UI does not have. |
| A hand-rolled service-with-subject cache | Cheaper than NgRx and still introduces cache invalidation, the specific hazard being avoided: showing stale salary figures. |

**Consequences.**

- *Gained:* no cache-invalidation class of bug; the server is always the source of truth; far less code.
- *Cost:* revisiting a screen refetches, so more requests and occasional visible reloads. Acceptable for an internal admin tool.

**Revisit when** a screen needs to react to another screen's mutation without a refetch.

---

## ADR-020: Single currency and single legal entity

**Status:** Accepted

**Context.** [requirements.md §2.4](requirements.md#24-assumptions-and-dependencies) fixes
one organisation, INR, and one jurisdiction.

**Decision.** No currency column and no tenant column. Amounts are INR by construction;
formatting uses Indian digit grouping.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Currency code on every amount, with a money value object | Correct for a multi-currency future, and it makes every amount, comparison, and total carry a currency that is always the same value. Premature. |
| Tenant column from day one | Cheap to add now, but every query gains a predicate that must never be forgotten — a security-relevant footgun for a feature nobody has asked for. |

**Consequences.**

- *Gained:* the simplest possible money handling; no tenant filter to omit; no cross-currency totalling questions.
- *Cost:* adding a second currency or legal entity is a schema migration plus a sweep of every aggregate query — a contained change, but not a small one.

---

## ADR-021: Aggregate compensation analytics in the application, not in SQL

**Status:** Accepted

**Context.** FR-7.2 and FR-7.4 ask what the organisation's current packages cost, split by
department and grade. Computing a package's gross is not a plain `SUM`: a
`PERCENT_OF_BASIC` component depends on the basic within the same structure, and every
component is rounded individually before summation (ADR-006, NFR-3.3).

**Decision.** Read the active cohort and its current revisions (three queries, batched),
then price and aggregate in the application using the same
`SalaryStructureCalculator` every other caller uses.

**Alternatives considered.**

| Alternative | Why rejected |
| --- | --- |
| Aggregate in SQL with a CTE resolving basic per structure | The fastest option, and it puts a second implementation of the rounding and percent-of-basic rules in a query. Two implementations of money arithmetic is precisely what ADR-006 exists to prevent, and the copy in SQL would be the one nobody unit-tests. A cost report that disagreed with a payslip by a rupee would be worse than a slow one. |
| Denormalise gross/net/CTC onto `salary_structures` at assignment time | Genuinely attractive: one implementation (the calculator, at write time) and cheap aggregation. Deferred because it adds columns, a migration and a backfill for a report that is fast enough at this scale — and because a stored figure and a recomputed one can drift, which is a new class of bug. This is the first thing to do if the report gets slow. |
| A materialised view refreshed on a schedule | Same duplication problem as the CTE, plus staleness in a report about salaries. |

**Consequences.**

- *Gained:* exactly one implementation of compensation arithmetic, so analytics, previews and payslips cannot disagree; the whole aggregation is unit-testable with no database; no new schema.
- *Cost:* the report loads every active employee and their current revision into memory. At the few thousand employees this system targets that is the same order of work a payroll run already does (architecture §5.3), but it is a ceiling, and it grows with headcount rather than with the size of the answer.
- *Also:* the figures price the packages *in force now*. They are not a payroll register — no attendance, no loss of pay — so they will differ from an actual month's payroll wherever someone has unpaid days. The API documents that in as many words, because a number labelled "monthly cost" invites exactly that misreading.

**Revisit when** the overview endpoint approaches the 500 ms p95 budget in NFR-1.1, or
headcount passes a few thousand — then denormalise the totals onto the structure row at
assignment time, keeping the calculator as the single source of the arithmetic.

---

## Tradeoff summary

### What was bought, and what it cost

| Decision | Bought | Paid |
| --- | --- | --- |
| Modular monolith (001) | Atomic payroll runs, one deployable | Scales as one unit; boundaries enforced by review only |
| Spring Boot (002) | Native decimals, declarative transactions, method security | Verbosity, slow startup |
| Angular (003) | Structure given, not assembled | Heavier bundle, steeper curve |
| Stateless JWT (004) | Any instance serves any request | Up to 60 min of unrevokable access |
| PostgreSQL only (005) | Exact decimals, real constraints | Single point of failure; reports share the instance |
| Per-component rounding (006) | Payslips whose columns add up | A few paise of arithmetic purity |
| Flyway + `validate` (007) | No schema drift, ever | A migration for every entity change |
| DTOs everywhere (008) | Opt-in exposure of fields | Mapper boilerplate |
| Append-only structures (009) | Reproducible history | Date-predicated resolution, unbounded rows |
| Draft/finalise (010) | A review gate and immutable records | No correction path after finalisation |
| Synchronous runs (011) | True atomicity, no orchestration | Long request; ceiling at a few thousand employees |
| Testcontainers (012) | Migrations and constraints verified | Docker required; slower tests |
| App-level audit (013) | Business-meaningful, actor-attributed trail | Manual call sites that can be forgotten |
| Soft delete (014) | History intact | Every query needs a status filter |
| Flat roles (015) | Readable, exhaustively testable authorisation | A new role shape needs code |
| Two calculation types (016) | No user input evaluated as code | New calculation shapes need code |
| On-demand PDFs (017) | One source of truth, nothing to secure | CPU per download; look of old payslips can change |
| Single artifact (018) | One version, one origin, no CORS | Frontend change redeploys the backend |
| No client store (019) | No cache invalidation bugs | More refetching |
| Single currency (020) | Simplest money handling | Migration if that assumption breaks |
| App-side analytics (021) | One implementation of the money arithmetic | Report loads the whole active cohort |

### The three tensions that shaped everything

**Correctness of money versus performance.** Correctness won every time it was contested.
Per-component rounding (006) costs a little arithmetic purity to keep payslips verifiable
by hand. A single-transaction run (011) gives up scalability to guarantee no partially paid
month. Real PostgreSQL in tests (012) costs seconds per run to catch the constraint
violations a faster in-memory database would hide. The payroll engine is the one place
where the design deliberately spends time, code, and test effort out of proportion to its
size.

**Simplicity versus flexibility.** Flexibility was consistently refused where no
requirement demanded it: no formula engine (016), no permission model (015), no tenant
column (020), no cache (005), no client state store (019), no job queue (011). The
discipline was to leave a *seam* rather than build the abstraction — an interface in front
of the calculator, package boundaries in the monolith, relative API paths in the SPA. Each
refused abstraction has a named revisit trigger, so the cost of being wrong is a contained
change rather than a rewrite.

**Security versus convenience.** Statelessness won over instant revocation (004), and that
is the design's most uncomfortable trade — stated openly rather than buried. Everywhere
else security took precedence: opt-in field exposure (008), authorisation on every PDF byte
(017), no user-authored expressions in the payroll process (016), ownership checks
independent of role (015).

### Decisions deliberately deferred

Not decided, because deciding now would be guessing:

- Whether audit events need a retention policy ([requirements.md open question 4](requirements.md#10-open-questions)).
- Whether reporting eventually needs materialised aggregates or a read replica.
- Whether an off-cycle adjustment mechanism is required — the known gap left by ADR-010.
- Whether MapStruct replaces hand-written mappers (ADR-008).
- Whether end-to-end browser tests are added on top of API-level acceptance tests.
