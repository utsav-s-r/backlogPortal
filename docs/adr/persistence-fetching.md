# ADR: Persistence — open-in-view off, and how each read path fetches its data

Status: Accepted — 2026-08-09 (OSIV off), extended 2026-08-11 (`type = LOAD` fix).
Branch: `rejectStatus`.

## Context

With Spring's default `open-in-view=true`, the Hibernate session stays open for the whole
request, so any lazy association resolves during view rendering. That hides fetch mistakes: an
N+1 looks like a working endpoint, and mapping code can touch lazy state from anywhere. It also
holds a DB connection for the duration of the request.

Turning it off makes the fetch strategy explicit — and makes every unfetched lazy access a
`LazyInitializationException` instead of a silent extra query.

## Decision

**`spring.jpa.open-in-view=false`.** Set in `application.properties` and
`application.properties.example` as `${JPA_OPEN_IN_VIEW:false}` (the env var is an emergency
escape hatch, not a normal knob), and **hard `false`** in
`src/test/resources/application-test.properties` — that file is checked in, so the context test
enforces the setting even if a developer's gitignored `application.properties` drifts.

**Fetch-join the to-ONE sides (`@EntityGraph`), batch-fetch the to-MANY sides (`@BatchSize`).**
These are not competing options; they fix N+1 for structurally different association types.

## The lazy surface is exactly one association

`Registration.subjects` (`@ManyToMany`, LAZY by JPA default, `@BatchSize(size = 30)`) is the
**only** lazy association in the model. Everything else is eager and cannot trigger a lazy load:

| Association | Mapping | Fetch |
|---|---|---|
| `Registration.student` | `@ManyToOne` | EAGER (JPA default) |
| `Registration.examCycle` | `@ManyToOne` | EAGER (JPA default) |
| `Subject.department` | `@ManyToOne` | EAGER (JPA default) |
| `Subject.eligibleDepartments` | `@ManyToMany(fetch = EAGER)`, `@BatchSize(size = 50)` | EAGER (explicit) |
| `User.department` | `@ManyToOne(fetch = EAGER)` | EAGER (explicit) |

So the question "does this code need a transaction?" always reduces to **"does it touch
`reg.getSubjects()`?"**

## Two traps that cost real bugs

**1. Never add a collection to an `@EntityGraph` on a PAGINATED query.** Hibernate abandons the
SQL `LIMIT` and paginates in memory, logging `HHH000104`. That converts a latency problem into an
unbounded-memory one. This is why the paginated admin list graphs only the to-one relations.

**2. `@EntityGraph` defaults to `EntityGraphType.FETCH`, which makes every attribute you did NOT
list LAZY — overriding the mapping.** So "it is mapped `FetchType.EAGER`, it cannot lazy-load" is
false on any query carrying a graph.

This bit for real (found 2026-08-11, pre-existing): the `findByRegId` graph silently demoted the
EAGER `Subject.eligibleDepartments`, and the dept-scope check walking it outside a transaction
threw `LazyInitializationException` — so a scope **denial returned 500 instead of 403**, on the
failure path only, where nothing looks. The fix is `type = LOAD`, which leaves unlisted attributes
at their mapped fetch type. Prefer `LOAD` over naming the nested path: two bag (`List`) fetches in
one query risk `MultipleBagFetchException`.

## Strategy per query (`RegistrationRepository`)

- **`findByRegId`** — `@EntityGraph{student, subjects, examCycle}`, **`type = LOAD`**. Single-row,
  so joining the collection is safe. **Load-bearing**: its callers (admin events, registration
  verify, student PDF) all walk `getSubjects()` inside the dept/proctor scope check, in a
  controller, outside any transaction.
- **`findByStudent_RollNo`** and **`findAll(spec, Sort)`** (PDF export) — same full graph,
  `type = LOAD`; both unpaginated, so the collection fetch is safe.
- **`findAll(spec, Pageable)`** (admin list) — `@EntityGraph{student, examCycle}` **only**.
  `subjects` loads lazily during mapping, collapsed by `@BatchSize(30)`.

Because that last one leaves `subjects` lazy, **its mapping must stay inside
`RegistrationService.listSummaries`, which is `@Transactional(readOnly = true)`**. The mapper
`toSummary` is deliberately `private` so a controller cannot call it outside the transaction;
`AdminController` only delegates, and all scope resolution stays in the controller.

**Silent-undo gotcha:** `@Transactional` engages via a Spring proxy, so it only applies to calls
from *outside* the class. Controller → `listSummaries` crosses the proxy (fine). If someone later
calls `listSummaries` from another method *inside* `RegistrationService`, the proxy is bypassed,
no transaction starts, and this breaks again — with no compile error and no failing test.

## Trade-offs (accepted)

- Mapping is pinned inside the service layer rather than being freely composable in controllers.
  Accepted: that constraint is what keeps the lazy load inside a transaction.
- The admin list hydrates `Subject` entities, which drag in EAGER `eligibleDepartments` that the
  list never displays. Accepted for now — see below.
- Measured cost of the admin list (25 rows, dev box, 2026-08-09): 5 SQL statements — auth user
  lookup, `Page` count, row query, `subjects` batch, `eligibleDepartments` batch. **Flat with
  respect to page size**, which is the property that matters.

## Alternatives considered

**Full DTO projection for the list — DEFERRED, not rejected.** It would stop the list hydrating
entities at all. Trigger to revisit: a measurement showing server-side work dominating, most
likely at large pages (`MAX_PAGE_SIZE=200`).

If implemented, use the **ID-pagination pattern** — apply the existing `Specification` to a
`CriteriaQuery<Long>` selecting `id`, paginate that, then project the IDs — so
`RegistrationSpecification` (which carries the proctor/dept scoping *and* the V6 `pg_trgm` search)
is never rewritten. Watch for: Postgres rejects `SELECT DISTINCT … ORDER BY` on an unselected
column (the spec sets `distinct(true)` when a subject filter is active), `IN` does not preserve
order, and an empty ID list is invalid SQL.

## Verifying a change here (do not trust a green suite)

Nothing in CI can catch a regression in this area. The backend suite only boots the Spring
context, and **every Cypress spec `cy.intercept`s its API calls**, so the e2e suite passes
regardless of what the persistence layer does.

Real proof: boot against local Postgres and curl the endpoints as a **dept-scoped role** — as
ADMIN the scope check returns early and never touches `getSubjects()`, so ADMIN proves nothing.
Then run a **negative control**: remove the `findByRegId` graph and confirm the events endpoint
500s with `failed to lazily initialize a collection … no Session`. Without that control, a passing
run proves nothing.

## Related

- `RegistrationRepository`, `RegistrationService.listSummaries`, `Registration`, `Subject`.
- Registrations are immutable history and carry `@Version`; concurrent verification is resolved by
  the optimistic lock, not by a last-write-wins overwrite.
- `docs/adr/backlog-progression.md` — the domain rules these queries serve.
