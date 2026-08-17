# ADR: Registration lifecycle — immutable history, a one-way state machine, and refusal codes

Status: Accepted — verification state machine and refusal split in force on `rejectStatus`.
Branch: `rejectStatus`.

## Context

A registration is the record a student takes to the department office to be signed. Once
submitted it is evidence: the printed form must keep showing what was true when it was generated,
and a verification decision must be auditable. That rules out both in-place editing and a
free-form status field.

## Decision

**1. Registrations are immutable history.** They snapshot the student's data at submission time
(`snap_name`, `snap_semester`, `snap_year_of_joining`, `snap_branch` — all `NOT NULL` as of
migration V7). Reads use the snapshot columns, never the live `Student` row.

A subject referenced by any registration **cannot be deleted** (409). Discontinue a subject by not
cloning it into the next academic year.

**2. Verification is a one-way state machine, and REJECTED is terminal.**

```
SUBMITTED -> VERIFIED
SUBMITTED -> REJECTED
VERIFIED  -> REJECTED      (override a completed verification)
```

There is no un-reject and no re-verify. Anything else is **409 "already actioned"**.

**3. The status flip and its audit event share ONE `@Transactional`**, so a status change can never
commit without its event row. The state is re-checked *inside* the transaction; a concurrent action
is caught either by that re-check or by the `@Version` optimistic lock on flush — yielding 409,
never a silent overwrite. Authorization stays with the caller (see `docs/adr/auth-scoping.md`).

**4. `verify/{regId}` requires an explicit action.** The controller accepts the literal strings
`"VERIFIED"` or `"REJECTED"` and answers **400** otherwise. A typo must never default to VERIFIED.

**5. The submit endpoint carries only `subjectIds`.** `snapSemester` — printed on the PDF as
"CURRENT SEMESTER OF THE STUDENT" — is read from `student.getCurrentSemester()` **server-side**.
It previously trusted a client-supplied `currentSemester` and printed the *backlog* semester being
registered, which was wrong on every form.

## Refusal codes: 400 versus 409

The rule, written on `RegistrationService.register`:

> **400 = the submission is wrong and the student can resubmit.
> 409 = the record isn't ready, and only staff can fix it.**

That distinction is the whole point — it decides whether the UI tells the student to change
something or to go talk to the department office. A 409 means no amount of retrying will help.

Related repo-wide conventions this depends on:

- **Id in the PATH → 404; id referenced from a BODY → 400.**
- **Never map `IllegalArgumentException` centrally to 400.** `NumberFormatException` extends it, so
  a blanket mapping would relabel real server bugs as client errors and hide them from ERROR logs.
  Wrap it at the call site instead.
- **Creates 201, deletes 204** — except `/admin/users/{u}/reset`, which stays 200 because it
  returns a credential and creates nothing.

## Registration window

`GET /api/registration-status` (`RegistrationStatusController`) is **public and unauthenticated**.
It returns `{open, cycleName, examMonthYear}` derived from `ExamCycleRepository.findByActiveTrue()`
— the *same* active-cycle check `register` enforces, so "is registration open?" has exactly one
source of truth. It drives the homepage badge and doubles as a health-check path.

## Sessions

Sessions are a **fixed 1h window and are not renewable — there is no refresh endpoint** (stated on
`JwtService`, and confirmed: none exists). The JWT is httpOnly, so JS cannot read `exp`; login
returns `expiresIn`, and the SPA persists the derived absolute `expiresAt` per scope.

The client-side timeout hook is **convenience only** — it signs the user out and warns near expiry.
The real cap is the JWT expiry the server re-checks on every request. Do not add a keep-alive:
there is nothing to keep alive.

## Trade-offs (accepted)

- A mistaken rejection cannot be undone; the student resubmits. Accepted — an un-reject would make
  the audit trail ambiguous about which decision was final.
- Snapshot columns duplicate student data by design. That duplication *is* the feature.
- `snap_email`, `snap_phone` and `snap_academic_year` stay nullable on purpose; V7 tightened only
  the four fields the printed form depends on.

## Verifying a change here

Exercise the **transitions that must fail**, not just the happy path: verify an already-VERIFIED
registration (expect 409), reject an already-REJECTED one (expect 409), and post a misspelled
action (expect 400, and confirm it did *not* silently verify). For concurrency, action the same
registration twice and confirm the loser gets 409 rather than overwriting.

## Related

- `RegistrationService` (`register`, `applyVerification`), `RegistrationController`,
  `RegistrationStatusController`, `Registration` (`@Version`, `snap_*`), `JwtService`.
- `docs/adr/auth-scoping.md` — who may action a registration.
- `docs/adr/persistence-fetching.md` — how these rows are fetched.
