# ACME Salary Management — Software Requirements Specification

**Version:** 0.1 (draft)
**Status:** Proposed — no implementation exists yet
**Last updated:** 2026-09-15

---

## 1. Introduction

### 1.1 Purpose

This document specifies the functional and non-functional requirements for the ACME
Salary Management application: a web system that lets an HR/payroll team maintain
employee compensation data, run a monthly payroll cycle, and publish payslips that
employees can view for themselves.

It is written for the engineers building the system, the reviewers assessing it, and
anyone writing test cases against it. Requirement IDs (`FR-*`, `NFR-*`) are stable and
may be cited from code, tests, and commit messages.

### 1.2 Scope

The system covers the salary lifecycle from employee onboarding to payslip delivery:

- Employee and organisation master data (departments, designations, grades).
- Salary structures — the components that make up a compensation package.
- A monthly payroll run that computes gross pay, deductions, and net pay.
- Payslip generation, retrieval, and PDF export.
- Role-based access so employees see only their own data.

Anything a real payroll system needs but this system deliberately does not attempt is
listed in [Section 9, Out of Scope](#9-out-of-scope).

### 1.3 Definitions

| Term | Meaning |
| --- | --- |
| **Salary component** | A named, typed element of pay — e.g. Basic, HRA, Provident Fund. Either an *earning* or a *deduction*. |
| **Salary structure** | The set of components, with amounts or formulas, assigned to one employee, effective from a given date. |
| **CTC** | Cost to company — annualised total of all employer-borne earnings. |
| **Gross pay** | Sum of all earning components for a payroll period. |
| **Net pay** | Gross pay minus all deductions for the period. |
| **Payroll run** | The act of computing payslips for every eligible employee for one month. |
| **Payslip** | The immutable record of one employee's pay for one payroll period. |
| **LOP** | Loss of pay — unpaid days that proportionally reduce earnings. |

### 1.4 References

- Project README: [../README.md](../README.md)
- Spring Boot reference documentation — https://docs.spring.io/spring-boot/
- Angular documentation — https://angular.dev/

---

## 2. Overall Description

### 2.1 System context

A single-page Angular application in the browser talks to a stateless Spring Boot REST
API over HTTPS. The API owns all business rules and persists to a relational database
(PostgreSQL) via JPA. Authentication is token-based (JWT); the browser holds no server
session.

```
┌────────────────┐    HTTPS / JSON     ┌──────────────────────┐     JDBC    ┌────────────┐
│  Angular SPA   │ ──────────────────► │  Spring Boot REST API│ ──────────► │ PostgreSQL │
│  (browser)     │ ◄────────────────── │  (stateless, JWT)    │ ◄────────── │            │
└────────────────┘                     └──────────────────────┘             └────────────┘
```

### 2.2 User classes

| Role | Description | Capability summary |
| --- | --- | --- |
| **ADMIN** | System owner. | Everything, including user administration and reference data. |
| **HR** | HR/payroll operator. | Manage employees and salary structures; execute payroll runs; view all payslips. |
| **EMPLOYEE** | Any salaried staff member. | View own profile, own salary structure, own payslips. |

### 2.3 Operating environment

- **Server:** Java 17 LTS, Spring Boot 3.x, PostgreSQL 15+.
- **Client:** Angular 17+, evergreen browsers (last two major versions of Chrome, Edge,
  Firefox, Safari). No Internet Explorer support.
- **Deployment:** Containerised (Docker); a `docker-compose` stack for local use.

### 2.4 Assumptions and dependencies

1. One organisation, one currency (INR), one legal jurisdiction. Multi-tenancy and
   multi-currency are not required.
2. Payroll is monthly, on a calendar-month period. No weekly or bi-weekly cycles.
3. Attendance and leave data are **entered by HR** as paid days / LOP days per employee
   per month; there is no attendance system to integrate with.
4. Tax and statutory deduction rules are configurable percentages or flat amounts. The
   system is not a certified tax engine and does not track slab-based income tax
   projections.
5. Email delivery of payslips depends on an SMTP relay being configured; if absent, the
   feature degrades to in-app download only.

---

## 3. Functional Requirements

### 3.1 Authentication and authorisation

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-1.1 | A user shall log in with email and password and receive a signed JWT access token. | Must |
| FR-1.2 | Passwords shall be stored only as BCrypt hashes; the API shall never return a password field. | Must |
| FR-1.3 | Every endpoint except login and health shall reject requests without a valid, unexpired token with HTTP 401. | Must |
| FR-1.4 | The API shall enforce role-based authorisation per endpoint and return HTTP 403 when the caller's role is insufficient. | Must |
| FR-1.5 | An EMPLOYEE requesting another employee's data shall receive HTTP 403, regardless of endpoint. | Must |
| FR-1.6 | A user shall be able to change their own password after confirming the current one. | Should |
| FR-1.7 | Access tokens shall expire within 60 minutes; the client shall be able to obtain a new token via a refresh token valid for 7 days. | Should |

### 3.2 Employee management

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-2.1 | HR shall create an employee record with: employee code, first/last name, work email, date of joining, department, designation, grade, and employment status. | Must |
| FR-2.2 | Employee code and work email shall be unique; a duplicate shall be rejected with HTTP 409 and a field-level message. | Must |
| FR-2.3 | HR shall update any editable field on an employee; employee code and date of joining shall be immutable after creation. | Must |
| FR-2.4 | HR shall list employees with server-side pagination, sorting, and filtering by name, department, designation, and status. | Must |
| FR-2.5 | HR shall deactivate an employee by recording an exit date and setting status to `INACTIVE`. Records shall never be hard-deleted. | Must |
| FR-2.6 | An `INACTIVE` employee shall be excluded from payroll runs for periods that begin after their exit date. | Must |
| FR-2.7 | Creating an employee shall provision a matching EMPLOYEE login whose username is the work email. | Should |
| FR-2.8 | HR shall export the filtered employee list as CSV. | Could |

### 3.3 Reference data

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-3.1 | ADMIN shall maintain departments (name, code, optional head). | Must |
| FR-3.2 | ADMIN shall maintain designations and grades, where a grade carries an optional minimum and maximum CTC band. | Must |
| FR-3.3 | Reference data in use by at least one employee shall not be deletable; the API shall respond HTTP 409. | Must |
| FR-3.4 | ADMIN shall maintain salary component definitions: name, code, type (`EARNING` / `DEDUCTION`), calculation type (`FLAT` / `PERCENT_OF_BASIC`), value, and taxable flag. | Must |

### 3.4 Salary structure

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-4.1 | HR shall assign a salary structure to an employee as a list of components with amounts or percentages, plus an effective-from date. | Must |
| FR-4.2 | Every structure shall include a `BASIC` earning component with a positive amount. | Must |
| FR-4.3 | The system shall reject a structure whose annualised gross falls outside the employee's grade CTC band, unless the caller supplies an explicit override reason. | Should |
| FR-4.4 | Assigning a new structure shall supersede — not overwrite — the previous one; the full revision history shall be retrievable. | Must |
| FR-4.5 | The system shall compute and display gross monthly, total deductions, net monthly, and annual CTC as a live preview before the structure is saved. | Must |
| FR-4.6 | A structure's effective-from date shall not precede the employee's date of joining. | Must |
| FR-4.7 | A structure already referenced by a finalised payroll run shall be immutable. | Must |

### 3.5 Payroll run

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-5.1 | HR shall initiate a payroll run for a given month and year. | Must |
| FR-5.2 | A payroll run shall include every `ACTIVE` employee holding a salary structure effective on or before the last day of the period. | Must |
| FR-5.3 | For each included employee the system shall compute gross pay, per-component deductions, and net pay from the structure effective in that period. | Must |
| FR-5.4 | Earnings shall be prorated by paid days ÷ total days in the period when LOP days are recorded. Deductions defined as `PERCENT_OF_BASIC` shall be computed against the prorated basic. | Must |
| FR-5.5 | A run shall move through the states `DRAFT → FINALISED`, and may move `DRAFT → CANCELLED`. A `FINALISED` run shall be immutable. | Must |
| FR-5.6 | While a run is `DRAFT`, HR shall be able to review every computed payslip, adjust LOP days, and recompute. | Must |
| FR-5.7 | The system shall reject a second run for a period that already has a `DRAFT` or `FINALISED` run, with HTTP 409. | Must |
| FR-5.8 | Finalising a run shall generate one payslip per included employee and make those payslips visible to those employees. | Must |
| FR-5.9 | A run shall complete within the performance budget in NFR-1.3 and shall be atomic: a failure mid-computation shall leave no partial run. | Must |
| FR-5.10 | The run summary shall report employee count, total gross, total deductions, and total net. | Should |

### 3.6 Payslips

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-6.1 | An EMPLOYEE shall list and open their own payslips, most recent first. | Must |
| FR-6.2 | A payslip shall show employer and employee identity, period, paid and LOP days, each earning and deduction line, gross, total deductions, and net pay. | Must |
| FR-6.3 | Net pay shall additionally be rendered in words. | Should |
| FR-6.4 | Any authorised viewer shall download a payslip as PDF. | Must |
| FR-6.5 | HR and ADMIN shall list payslips across all employees, filtered by period, department, or employee. | Must |
| FR-6.6 | Payslips shall be read-only once created; a correction shall be issued by cancelling and re-running the period, which is permitted only before finalisation. | Must |
| FR-6.7 | On finalisation the system shall email each employee their payslip PDF, when SMTP is configured. | Could |

### 3.7 Reporting

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-7.1 | HR shall view a monthly payroll register: one row per employee with gross, deductions, and net for a chosen period. | Should |
| FR-7.2 | HR shall view department-wise salary cost totals for a chosen period. | Should |
| FR-7.3 | HR shall export any report as CSV. | Could |
| FR-7.4 | The dashboard shall show headcount, current monthly payroll cost, and the last completed run. | Could |

### 3.8 Auditing

| ID | Requirement | Priority |
| --- | --- | --- |
| FR-8.1 | Every create, update, and state transition on employees, salary structures, and payroll runs shall record actor, timestamp, entity, and action. | Must |
| FR-8.2 | ADMIN shall query the audit trail by actor, entity type, and date range. | Should |

---

## 4. Non-Functional Requirements

### 4.1 Performance

| ID | Requirement |
| --- | --- |
| NFR-1.1 | 95th-percentile server response time for read endpoints shall be under 500 ms at 50 concurrent users. |
| NFR-1.2 | Paginated list endpoints shall return at most 100 records per page and shall page in the database, never in memory. |
| NFR-1.3 | A payroll run for 1,000 employees shall complete within 60 seconds. |
| NFR-1.4 | The SPA shall reach first contentful paint within 2 seconds on a 10 Mbps connection. |

### 4.2 Security

| ID | Requirement |
| --- | --- |
| NFR-2.1 | All traffic shall be over TLS 1.2 or higher. |
| NFR-2.2 | Authorisation shall be enforced server-side on every request; UI hiding is presentation only and is not a control. |
| NFR-2.3 | All input shall be validated server-side; validation failures shall return HTTP 400 with per-field messages. |
| NFR-2.4 | Database access shall use parameterised queries or JPA exclusively — no string-concatenated SQL. |
| NFR-2.5 | Secrets (DB credentials, JWT signing key, SMTP credentials) shall come from environment variables and shall never be committed. |
| NFR-2.6 | Error responses shall not leak stack traces, SQL, or internal class names. |
| NFR-2.7 | Salary data shall be accessible only to ADMIN, HR, and the owning employee. |

### 4.3 Reliability and data integrity

| ID | Requirement |
| --- | --- |
| NFR-3.1 | A payroll run shall execute in a single transaction, or in resumable batches that are individually atomic. |
| NFR-3.2 | Monetary values shall be stored and computed as fixed-point decimals (`NUMERIC(12,2)` / `BigDecimal`) — never floating point. |
| NFR-3.3 | Rounding shall be half-up to two decimal places, applied per component, and the payslip total shall equal the sum of its rounded lines. |
| NFR-3.4 | Schema changes shall be applied through versioned migrations (Flyway); the schema shall never be auto-generated in any deployed environment. |

### 4.4 Usability and accessibility

| ID | Requirement |
| --- | --- |
| NFR-4.1 | The UI shall be usable at viewport widths from 360 px upward. |
| NFR-4.2 | The UI shall meet WCAG 2.1 Level AA for contrast, focus visibility, and keyboard operability. |
| NFR-4.3 | Every form field shall have a programmatically associated label; errors shall be announced to assistive technology. |
| NFR-4.4 | Currency shall be displayed with the Indian digit grouping and a `₹` symbol. |

### 4.5 Maintainability and quality

| ID | Requirement |
| --- | --- |
| NFR-5.1 | Backend layering shall be controller → service → repository; controllers shall contain no business logic. |
| NFR-5.2 | The API shall expose DTOs only; JPA entities shall not be serialised to clients. |
| NFR-5.3 | Unit test line coverage shall be at least 70% overall and at least 90% for the payroll calculation package. |
| NFR-5.4 | The REST API shall be documented with OpenAPI, served at `/swagger-ui.html`. |
| NFR-5.5 | Build, test, and lint shall run in CI on every push; a failing check shall block merge. |

### 4.6 Observability

| ID | Requirement |
| --- | --- |
| NFR-6.1 | The API shall expose a liveness/readiness endpoint at `/actuator/health`. |
| NFR-6.2 | Logs shall be structured, shall carry a correlation ID per request, and shall never contain passwords, tokens, or full salary records. |

---

## 5. Data Model

Core entities and their relationships:

```
User ──1:1── Employee ──*:1── Department
                 │      ──*:1── Designation
                 │      ──*:1── Grade
                 │
                 ├──1:*── SalaryStructure ──1:*── SalaryStructureComponent ──*:1── SalaryComponent
                 │
                 └──1:*── Payslip ──1:*── PayslipLine
                              │
                       PayrollRun ──1:*── Payslip
```

| Entity | Key fields |
| --- | --- |
| `User` | id, email (unique), passwordHash, role, enabled |
| `Employee` | id, employeeCode (unique), firstName, lastName, workEmail (unique), dateOfJoining, exitDate, status, departmentId, designationId, gradeId |
| `Department` | id, code (unique), name |
| `Designation` | id, title |
| `Grade` | id, name, minCtc, maxCtc |
| `SalaryComponent` | id, code (unique), name, type, calculationType, defaultValue, taxable |
| `SalaryStructure` | id, employeeId, effectiveFrom, supersededOn, createdBy |
| `SalaryStructureComponent` | id, structureId, componentId, value |
| `PayrollRun` | id, periodMonth, periodYear (unique together), status, initiatedBy, finalisedAt, totals |
| `Payslip` | id, payrollRunId, employeeId, paidDays, lopDays, grossPay, totalDeductions, netPay |
| `PayslipLine` | id, payslipId, componentCode, componentName, type, amount |
| `AuditEvent` | id, actorId, entityType, entityId, action, occurredAt, details |

Constraints worth calling out: `(periodMonth, periodYear)` is unique on `PayrollRun`;
`(payrollRunId, employeeId)` is unique on `Payslip`; all monetary columns are
`NUMERIC(12,2)`.

---

## 6. API Surface

Base path `/api/v1`. All paths require a bearer token except `POST /auth/login`.

| Method | Path | Roles | Purpose |
| --- | --- | --- | --- |
| POST | `/auth/login` | public | Exchange credentials for tokens |
| POST | `/auth/refresh` | any | Exchange refresh token for a new access token |
| GET | `/employees` | ADMIN, HR | Paginated, filterable employee list |
| POST | `/employees` | ADMIN, HR | Create employee |
| GET | `/employees/{id}` | ADMIN, HR, self | Employee detail |
| PUT | `/employees/{id}` | ADMIN, HR | Update employee |
| POST | `/employees/{id}/deactivate` | ADMIN, HR | Record exit and deactivate |
| GET | `/employees/{id}/salary-structures` | ADMIN, HR, self | Structure history |
| POST | `/employees/{id}/salary-structures` | ADMIN, HR | Assign new structure |
| GET | `/salary-components` | ADMIN, HR | List component definitions |
| POST | `/salary-components` | ADMIN | Create component definition |
| GET | `/departments`, `/designations`, `/grades` | ADMIN, HR | Reference data |
| POST | `/payroll-runs` | ADMIN, HR | Start a draft run for a period |
| GET | `/payroll-runs` | ADMIN, HR | List runs with totals |
| GET | `/payroll-runs/{id}` | ADMIN, HR | Run detail with draft payslips |
| POST | `/payroll-runs/{id}/recompute` | ADMIN, HR | Recompute a draft run |
| POST | `/payroll-runs/{id}/finalise` | ADMIN, HR | Finalise and publish payslips |
| POST | `/payroll-runs/{id}/cancel` | ADMIN, HR | Cancel a draft run |
| GET | `/payslips` | ADMIN, HR | All payslips, filterable |
| GET | `/payslips/me` | EMPLOYEE | Own payslips |
| GET | `/payslips/{id}` | ADMIN, HR, owner | Payslip detail |
| GET | `/payslips/{id}/pdf` | ADMIN, HR, owner | Payslip as PDF |
| GET | `/reports/payroll-register` | ADMIN, HR | Monthly register |
| GET | `/audit-events` | ADMIN | Audit trail query |

Errors use a single envelope: `{ "timestamp", "status", "error", "message", "path", "fieldErrors": [] }`.

---

## 7. Acceptance Criteria

The release is acceptable when all **Must** requirements are implemented and these
end-to-end scenarios pass:

1. **Onboard and pay.** HR creates an employee, assigns a structure with Basic 50,000 +
   HRA 20,000 − PF 6,000, runs payroll for the current month, and finalises it. The
   employee logs in and sees one payslip with gross 70,000, deductions 6,000, net 64,000.
2. **Proration.** The same employee with 5 LOP days in a 30-day month yields earnings
   prorated to 25/30, and PF computed on the prorated basic.
3. **Access control.** Employee A requests employee B's payslip by id and receives 403.
4. **Duplicate period.** A second payroll run for an already-finalised period is rejected
   with 409.
5. **Immutability.** An attempt to edit a salary structure referenced by a finalised run
   is rejected, and the structure history still shows both revisions.
6. **Exit.** A deactivated employee with an exit date before the period start is absent
   from the next run.

---

## 8. Traceability

Each requirement ID is expected to map to at least one automated test. The mapping lives
alongside the tests (test class names cite the ID, e.g. `PayrollRunServiceTest` →
`FR-5.4`), rather than being duplicated here.

---

## 9. Out of Scope

Explicitly not built, and not to be inferred from anything above:

- Statutory tax computation beyond configurable flat/percentage deductions; no income
  tax slabs, Form 16, or regulatory filings.
- Bank transfer files, payment gateway integration, or any actual disbursement.
- Attendance, leave, or time-tracking modules; LOP is entered manually.
- Reimbursements, bonuses driven by external appraisal data, loans, or advances.
- Multi-company, multi-currency, or multi-country payroll.
- Mobile applications; the responsive web UI is the only client.
- Self-service employee profile edits or workflow approvals.

---

## 10. Open Questions

| # | Question | Owner |
| --- | --- | --- |
| 1 | Should payslip PDFs be generated at finalisation and stored, or rendered on demand? | TBD |
| 2 | Is a refresh-token flow required for the assessment, or is a single 60-minute token enough? | TBD |
| 3 | What Basic-to-CTC ratio, if any, should be enforced when a structure is assigned? | TBD |
| 4 | Does the audit trail need retention rules, or is unbounded retention acceptable? | TBD |
