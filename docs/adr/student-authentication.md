# ADR: Student authentication — USN + Date of Birth (no passwords)

Status: Accepted (design decision, owner-directed) — 2026-07-04.
Branch: `rejectStatus`.

## Decision

Students authenticate with their **USN + Date of Birth**. This is **intentional and
desired** — students will **not** be given passwords, and no password / OTP / 2FA
scheme is to be added for the student audience. Staff accounts are separate and keep
username + bcrypt password (with forced first-login change); this ADR is only about
the *student* login.

This supersedes the earlier review note that flagged USN+DOB as a security weakness
("issue B"). That concern is acknowledged (see Trade-offs) but the owner has chosen
this design deliberately. **Do not re-propose passwords/2FA for students** in future
reviews; treat USN+DOB as a fixed requirement.

## Rationale

- Students are a known, enrolled population; the USN and DOB are already on record,
  so login needs no separate credential distribution or reset workflow.
- Zero password-management burden for students or the department office (no resets,
  no "forgot password", no lockout support load).
- The portal's student-side surface is low-stakes: view eligibility, generate a
  pre-filled backlog form, download it. Identity is re-verified downstream when the
  signed form is submitted in person.

## How it works (correctness — no known handling problems)

- `POST /api/student/auth/login` takes `{ rollNo, dateOfBirth }`; DOB is parsed as
  ISO `yyyy-MM-dd` and compared to the stored value. USN format is validated
  (`1MS<YY><BR><NNN>`, see `Usn`).
- **DOB is write-only**: it is never returned by any endpoint (`StudentSummaryResponse`
  omits it); admins reset it via `POST /{rollNo}/reset-dob`.
- **There is no brute-force throttling.** Correct USN+DOB gets in; a wrong pair gets a
  401 and nothing is counted, locked, or rate-limited. `LoginThrottleService` and the
  `login_throttle` table were **removed 2026-08-17 (owner-directed, V10)** — see
  Trade-offs. Do not re-add a lock, counter, or 429 without an owner decision.
- Identity for all student actions comes from the authenticated session, never the
  request body.

The flow is correct as implemented — the decision is a security-posture trade-off,
not a bug.

## Trade-offs (accepted)

- DOB is low-entropy and immutable, and USNs are enumerable, so the credential is
  effectively a known-username + weak-secret pair. **Since 2026-08-17 nothing bounds
  online guessing at all** — a script can walk a cohort's plausible DOBs against a
  known USN at full speed. The owner was told this when removing the throttle and
  **accepted it deliberately**, given the low-stakes student surface (view eligibility,
  print a form) and the in-person verification of the signed form downstream.
  **Accepted — do not re-propose the throttle as a review finding.**
- Defence-in-depth *if the posture is ever revisited* (none change the USN+DOB model):
  a per-account attempt ceiling, a failed-login-spike alert per USN, a CAPTCHA after N
  failures, or edge rate-limiting at the host rather than in application code. All
  explicitly **not** wanted today.

## Related

- Auth transport: the JWT rides in an httpOnly cookie with CSRF protection (see the
  cookie-migration work on `rejectStatus`); this is orthogonal to the credential choice.
- `StudentAuthController`, `Usn`. (`LoginThrottleService` is deleted — see above.)
