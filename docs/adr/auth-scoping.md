# ADR: Authorization scoping — fail closed, and what `null` is allowed to mean

Status: Accepted — 2026-08-11 (fail-open closed; supersedes the earlier permissive behaviour).
Branch: `rejectStatus`.

Scope: how an authenticated caller's *authority* is resolved and enforced. The student credential
choice is a separate decision — see `docs/adr/student-authentication.md`.

## Context

Five staff roles act on the same endpoints with different reach: ADMIN and PRINCIPAL act broadly;
HOD and DEPT_OFFICE are pinned to their own department; PROCTOR is dept-pinned **and** hard-scoped
to an explicitly assigned set of students.

Every controller resolved this with its own copy of the same private helper — nine byte-identical
copies. Those helpers returned a permissive `null` when the caller could not be identified, and
every call site reads `null` as "ADMIN/PRINCIPAL, unrestricted."

That was a **privilege escalation, not a stale-session annoyance**. `JwtAuthenticationFilter` is
stateless, so a token outlives the account it names. Proven against a local database: a **deleted**
CSE HOD's still-valid cookie listed another department's subjects and *created* a subject there,
and a deleted ADMIN opened and closed registration college-wide.

## Decision

**1. A scope resolver's `null` now means one thing only: "a genuinely unrestricted ADMIN/PRINCIPAL."**
Every other outcome is an explicit refusal. Do not reintroduce a third meaning for `null`.

| Situation | Result |
|---|---|
| Not authenticated | **401** "Not authenticated" |
| Token valid, `users` row gone | **401** "Unknown account" |
| Row exists, `role` is NULL | **403** "No role assigned to your account." |
| Dept-scoped role, no department | **403** "No department assigned to your account." |
| ADMIN / PRINCIPAL | `null` — unrestricted, by design |

**"Unrestricted" is about DEPARTMENT scope, not about every action.** One carve-out exists:
**PRINCIPAL cannot verify or reject a registration** — that is the departments' call, not the
registrar's. Asserted in three places that must stay in step: the `/api/register/verify/**` matcher
in `SecurityConfig`, `RegistrationController#verifyRegistration`'s `@PreAuthorize`, and the
dashboard's `adminRole !== "PRINCIPAL"` button gate. Both server-side checks fail closed
independently; `admin-access.cy.js` asserts the UI half.

**2. Identifying the caller lives in one place: `service/CallerScope.java`.** `requireActor` throws
rather than returning a sentinel, so the fail-closed contract is stated once and is unit-testable —
private controller helpers are not, and this repo has no controller tests.

**3. Revocation lives in exactly one place: `AccountExistenceFilter`.** It 401s when an
admin principal's `users` row no longer exists.

**4. Enforcement is server-side on every admin endpoint.** UI gating is a convenience, never the
control. Cypress cannot catch a violation here — every spec stubs its API calls.

## Rationale

**Why `CallerScope` and not nine helpers.** The duplication *was* the bug: the same defect existed
in five of the nine copies, and fixing one taught you nothing about the others.

It also handles a case each copy got wrong: `users.role` is nullable and its CHECK constraint
admits NULL (`NULL = ANY(...)` evaluates to NULL, not false), so a hand-edited row is storable.
Without the explicit check, every `DEPT_ROLES.contains(role)` call NPEs — `Set.of()` rejects a null
lookup — turning a broken account into a 500 at nine separate sites.

**Why the revocation check sits in `AccountExistenceFilter`.** The filter was originally
`PasswordChangeEnforcementFilter`, and the revocation check was added there because it already
loaded that `users` row for the forced-password-change gate, so it cost no extra query. The forced
change was removed on 2026-08-15 (see below) and the class renamed; the check stays because the
other two reasons still hold — it is admin-only, and it covers endpoints that never resolve a
departmental scope at all (exam cycles, department CRUD), which a scope-resolver-only fix would
have missed. It is now the class's only job, and a `existsById` rather than a full load.

**Forced password change, removed 2026-08-15.** Accounts were created and reset with a random
one-time password (`TempPasswordGenerator`), shown once in the UI, with a `users.must_change_password`
flag that this filter enforced with a 403 `PASSWORD_CHANGE_REQUIRED` on every endpoint but
change-password. That is gone: V8 drops the column, and create/reset now install a derived default,
`username + "4321"`. Owner's decision — the ceremony was judged excessive for a college portal whose
managers hand out credentials in person. The cost is explicit and accepted: a staff account's
password is derivable from its username, which is displayed in Manage Users, so the login endpoint
will accept a first guess from anyone who knows the convention. Throttling never mitigated this —
the *first* guess succeeds, so a failure counter was irrelevant here even before
`LoginThrottleService` was removed (2026-08-17, V10; see `student-authentication.md`). What
preserves a way out is that
`POST /api/auth/change-password` stays, and the dashboard header links it for **all five roles** —
Manage Users only reaches ADMIN/PRINCIPAL/HOD, so before this change DEPT_OFFICE and PROCTOR would
have had no route to it at all. `CreateUserRequest` also gained `@Size(min = 4)` on the username
(trimmed in the setter, since Jackson binds before validation) so the derived default always clears
the 8-character floor `ChangePasswordRequest` puts on chosen passwords.

**Never move this into `JwtAuthenticationFilter`.** That filter is shared with STUDENT tokens, and
students are not rows in `users` — every student request would 401.

**Logout is never gated.** The filter exempts `/api/auth/logout` from both its checks. Gating it
stranded precisely the accounts that most needed to sign out.

## Proctor scope

`ProctorScopeService` is the sibling primitive: `CallerScope` answers *who is calling*, this
answers *which students may they touch*.

- `assignedRollNos(actor)` returns `null` for a non-proctor — unrestricted, consistent with the
  rule above — and the assigned set otherwise.
- An **empty** assigned set must yield an empty page or a zero count, **never an unfiltered query**.
  Scoping flows through `RegistrationSpecification.studentRollNos`, so the restriction is applied
  in SQL rather than after the fact.
- `assertSupervises` and `rejectProctor` throw **403**.
- A proctor's scope is the *student*, so it reaches that student's registrations transitively.

## Status codes (the contract the SPA depends on)

- **401 = no valid session → sign out. 403 = authenticated but denied → show it in place.**
  `SecurityConfig` wires both explicitly; Spring's default entry point is
  `Http403ForbiddenEntryPoint`, which would answer 403 for *unauthenticated* requests and break the
  distinction.
- `frontend/src/lib/api.js` handles 401 by signing out and **deliberately does not handle 403** —
  a 403 logout interceptor would sign users out of pages they merely lack permission for. The one
  exception is a 403 carrying `code: "PASSWORD_CHANGE_REQUIRED"`, routed to the change-password
  flow.

## Trade-offs (accepted)

- Each controller keeps its own `DEPT_ROLES` set, deliberately **not** folded into `CallerScope`.
  They genuinely differ — `{HOD, DEPT_OFFICE, PROCTOR}` in most, `{HOD, DEPT_OFFICE}` where PROCTOR
  is excluded by `@PreAuthorize` or handled earlier — so one shared set would silently widen or
  narrow one of them. That is the exact bug class this pass removed.
- Revocation is checked per request against the `users` table, so a deleted account is cut off at
  the next request rather than instantly. Accepted: sessions are a fixed 1h and non-renewable.
- Identity always comes from the JWT, never a request body. Branch and admission year are derived
  from the USN format `1MS<YY><BR><NNN>`.

## Verifying a change here

Add a **negative** test, not just a positive one: the fail-open bug lived entirely on the denial
path, where nothing looks. Curl as a dept-scoped role against another department's data and assert
the refusal — as ADMIN the scope check returns early and proves nothing. Confirm a deleted
account's still-valid cookie 401s, and that logout still succeeds for an account failing both
gates.

## Related

- `CallerScope`, `ProctorScopeService`, `AccountExistenceFilter`, `SecurityConfig`,
  `RegistrationSpecification`, `frontend/src/lib/api.js`.
- `docs/adr/student-authentication.md` — the student credential decision.
- `docs/adr/persistence-fetching.md` — an entity-graph trap that made one scope **denial** 500
  instead of 403.
