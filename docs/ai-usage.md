# How this was built with AI

The brief asks for the artifacts that explain the approach, and names prompts and
instructions among them. This is that document: which tools were used, how the work was
directed, and — the part worth reading — **what was checked before anything was accepted**.

A claim of "AI-accelerated" is only meaningful if you can see where human judgment was
applied. So this records the corrections as carefully as the successes.

---

## 1. Tooling

| | |
| --- | --- |
| Agent | Claude Code (Opus), run inside VS Code against this repository |
| Mode | Agentic — the model reads and edits files, runs the build, the tests and the linter, and iterates on their output |
| Human role | Requirements and scope, architectural decisions, review of every diff, and the final say on anything the tests could not settle |

No other code-generation tool was used. There is no generated code that nobody read.

---

## 2. The order the work was done in, and why that order

The commit history is the evidence for this section; each phase names its commits.

1. **Requirements before any code** — `71f84c6 docs: initialize project structure and
   requirements`. This is the repository's first commit, and it predates every line of
   Java. The brief asks for a requirements document written before building; doing it in
   that order is what makes the numbered `FR-*`/`NFR-*` ids usable as the vocabulary
   everything else refers to. Test classes and code comments cite those ids throughout,
   which is only possible because they existed first.
2. **Architecture and decisions** — `942f585 docs: modified and added architecture.md and
   decisions.md`. Written before the domain, so the layering, the money rule and the
   error envelope were decisions rather than accidents of the first implementation.
3. **Backend in vertical slices** — `970559f` scaffold, `0016a7f` employee domain,
   `5b22695` search and paging, `889785c` salary management, `7ce302c` business-rule
   tests, `370aa1b` compensation analytics, `001ff1e` JWT auth, `8fa4c0e` the curated
   seed. Each slice ends with its own tests, so the suite grew with the code rather than
   being retrofitted.
4. **Frontend, screen by screen** — `fa80d42` scaffold, `da4f002` list state in the URL,
   `f05bc95` assignment form, dashboard and components admin, `6493502` payslips,
   `6785a53` the payroll-run screen.
5. **Scale and artifacts** — the 10,000-employee seed and this document.

---

## 3. How the AI was directed

The prompting patterns that produced the most useful output, as reusable shapes rather
than a transcript:

- **"Give me the alternatives and what each costs, then recommend one."** This is what
  produced [decisions.md](decisions.md): 22 ADRs that each carry the rejected options and
  the price of the choice. Asking only for a solution gets you a solution with no visible
  reasoning; asking for the trade-off space gets you something reviewable.
- **"Explain why in the comment, not what."** The code comments say why a percentage
  deduction is not prorated twice, why `DatePipe` is unsafe for a `LocalDate`, why the
  interceptor order is load-bearing. A comment restating the line above it is noise; a
  comment holding the reason is the only copy of that reasoning.
- **"Write the test that would fail if this rule were wrong."** Used on the payroll
  calculator, where the interesting cases are not the happy path: the test suite covers
  proration per component kind and the invariant that displayed lines always sum to
  displayed totals at every loss-of-pay value.
- **"What did you not verify?"** Asked at the end of a task. This is where the honest
  answers live — integration tests skipping without Docker, the seed never executed
  against a live database — and it is the question that keeps a summary from overstating.
- **Requirement ids as the shared vocabulary.** "FR-5.8 says payslips exist from creation,
  not finalisation" is unambiguous in a way that "payslips should be visible early" is not.

### What the AI was not allowed to decide

Some choices were made by hand and then enforced on everything the model wrote, because
getting them wrong is expensive and hard to reverse:

- **Money is `BigDecimal` and `NUMERIC(12,2)`, never a float**, and crosses the wire as a
  string that the browser formats without parsing (ADR-006, architecture §6.3).
- **Rounding is half-up per component, before summation**, so a payslip's lines always add
  up to its total. This costs a little mathematical purity and buys arithmetic a person
  can check by hand.
- **Authorisation is the API's job**, never the UI's. Guards and hidden menu items are a
  courtesy; `@PreAuthorize` and the record-level ownership checks are the control.
- **Scope.** What this system deliberately does not do — multi-currency, multi-country,
  attendance integration, user-authored formulas — is a product decision recorded in
  [requirements.md](requirements.md), not something an agent inferred.

---

## 4. Verification: what was actually checked

Nothing here is "the model said it works". Every task ended with evidence.

**Always:**

```bash
cd backend  && ./mvnw test      # 546 tests (44 are Testcontainers ITs, skipped without Docker)
cd frontend && npm test         # 396 tests
cd frontend && npm run lint     # ESLint over TypeScript and templates
cd frontend && npm run build    # the production bundle must compile
```

**Where the ordinary checks could not reach, something else was built to reach it:**

| Claim | How it was verified |
| --- | --- |
| The 10,000-row seed is valid PostgreSQL | Parsed with `libpg_query` (the server's own grammar, via `pglast`), including the PL/pgSQL body of its verification block. The known-good migrations were parsed too, to prove the method could detect a difference. |
| Its arithmetic puts every package inside its grade band | The formulas were re-implemented in Python with `Decimal(ROUND_HALF_UP)` to mirror Postgres `numeric`, then all 9,949 packages were checked: 0 out of band, 0 non-positive basics, 0 packages deducting more than they pay, and the lines summing to gross exactly in every case. |
| The seed stays correct after future edits | It verifies itself. A `DO` block re-costs every package the way the calculator does and fails startup with a named reason if a count is wrong or a package would have been rejected by the API. |
| The seeded figures quoted in the README are real | `DevSeedFiguresTest` parses the seed SQL, prices it with the production calculator and asserts the documented totals. |
| The favicon is legible at 16px | Rendered and **looked at**. The first version's crossbar sat high enough that the counter closed into a blob at tab size; the mark was adjusted and re-rendered. |
| The hand-assembled `.ico` is a valid container | Parsed back byte by byte — three directory entries, each a valid PNG at its declared size, every offset in range — then confirmed with `file`. |
| The lockfile resolves from the public npm registry | `npm ci` into a clean directory **with an empty cache**, proving the integrity hashes match the public tarballs and not just that the URLs look right. |

---

## 5. Where the AI was wrong, and what caught it

The useful half of this document.

| What went wrong | What caught it |
| --- | --- |
| A `computed()` wrapped around a `FormControl.value` — which never recomputes, because a control's value is not a signal. The unit label on the component form was stuck on whatever the form opened with. | A unit test. Now recorded in the frontend README so it is not rediscovered. |
| The payroll-run result heading read from the form controls, so changing the dropdowns after a run relabelled a draft that had already been computed. | Reviewing the behaviour rather than the diff; now pinned by a test that changes the selection after a successful run. |
| A server refusal stayed pinned under the period dropdown after the user picked a different month, attributing "2026-08 already has a run" to a month the server never saw. | Reading the interaction end to end; fixed and tested. |
| The seed's verification block nested an aggregate inside an aggregate — invalid SQL that would have failed at startup. | The PostgreSQL parser, before the file was ever run. |
| The favicon's SVG comment contained `--`, which is illegal inside an XML comment. | The rasteriser refused the file. |
| Dependencies silently resolved through a corporate Artifactory mirror, baking 692 internal URLs into `package-lock.json`. | Inspecting the lockfile's hosts. Fixed project-scoped, in the repository's own `.npmrc`, without touching machine configuration that other work depends on. |
| Documentation drifting ahead of the code — claiming payroll runs had "no UI" after one was built, and describing `features/payslips` as unbuilt. | Grepping the docs for claims about the thing being changed, as part of the change. |

The pattern: **the failures were caught by something mechanical** — a test, a parser, a
build, or rendering the artifact and looking at it. None were caught by reading the
generated code and finding it plausible, which is the reason this section is not a list of
things noticed by eye.

---

## 6. What is still unverified

Stated here rather than left to be discovered:

- **The bulk seed has never been executed against a live PostgreSQL.** The machine it was
  written on has no Docker and no local server. Its syntax is parser-verified and its
  arithmetic is model-verified, and it carries a self-check that fails loudly — but
  "parsed and modelled" is not "ran".
- **The Testcontainers integration tests (44) skip without Docker.** A green build on a
  machine without Docker has not verified the schema, the column types, or the queries.
- **Nothing is deployed**, and there is no CI pipeline, so no build has been proven on a
  machine other than a development one.
- **The payroll cycle is only partly reachable from the UI** — a run can be started, but
  reviewing, adjusting loss of pay, finalising and cancelling are API-only.
- **The 10,000-employee seed makes NFR-1.3 a live question**, not a theoretical one: a run
  over this dataset is ~9,900 payslips in one transaction against a 60-second budget
  written for 1,000.

---

## 7. Prompt excerpts

This section is for verbatim excerpts of the instructions given to the agent, which only
the author can supply — the shapes in §3 are reconstructed from what the repository shows,
not from a saved transcript. Paste the sessions worth showing here.
