# Maintainer notes

Working knowledge for whoever maintains this codebase next: the domain rules, the traps that fail
silently, the conventions, and the ideas already tried and rejected. Read the
[README](../README.md) first for setup, deployment and environment variables; the database schema
and backup procedures are in [DATABASE.md](DATABASE.md).
The long-form reasoning for several areas is in [`docs/adr/`](adr).

> **Some facts here may be outdated.** The code changes and notes drift. Wherever a statement can be
> checked against the code (a class name, a limit, a status code, which roles can do what), check
> it before relying on it, and treat the code as the source of truth. If you find something wrong,
> fix this file.

Many rules below say "fails silently". That is the point of this file: the build, lint and test
suite stay green when they are broken, so nothing but knowing about them protects you.

---

## 1. Accounts and test data

There are no shared test credentials. You set up your own database and create your own accounts.

- **The first login.** On a fresh database exactly one account is created automatically: `admin`,
  with the password from `ADMIN_PASSWORD_ADMIN` (only if no ADMIN exists yet). Remove that variable
  after the first sign-in. Everyone else is created from **Manage Users**.
- **Order of setup.** HOD, DEPT_OFFICE and PROCTOR accounts must belong to a department, so create
  departments first, then staff, then students (or import them by CSV).
- **New staff passwords.** A newly created or reset staff account gets a default password derived
  from its username (see `CreateUserRequest`). Tell staff to change it on first login.
- **Students** sign in with USN + date of birth; there is no student password. Staff can reset a
  date of birth but never read it.
- **Build test data that can catch scoping bugs.** Create at least two departments, and at least one
  student in a department *other than* the one your HOD / DEPT_OFFICE / PROCTOR test accounts belong
  to. As those accounts, that student must be absent from lists and return 403 on per-student
  endpoints; as ADMIN/PRINCIPAL it must be visible. Without that control student you cannot tell
  correct scoping from no scoping.
- **Test scoping as the department-limited roles, never as ADMIN.** ADMIN skips the scope checks,
  so a test as ADMIN proves nothing. A proctor with no assigned students must see an empty list,
  never an unfiltered one.
- **Use `example.com` emails for test students.** Reminders go to students' real email addresses.
  Preview a reminder's audience count before sending.
- **Sessions.** One hour, not renewable. Changing your own password or username signs you out on
  purpose, and so does an admin renaming you or resetting your password.
- **Automated tests need no credentials.** Cypress fakes sessions with `cy.visitAsAdmin(path, {role,
  username, department, departmentId})` and `cy.visitAsStudent(path, {rollNo, name})` (in
  `cypress/support/e2e.js`). Pass `departmentId` for department roles — seeding only the name
  silently tests a fallback path. Backend tests create their own users.
- **Dev sample data.** Running with the `dev` profile adds `db/devseed/R__dev_seed.sql`: two demo
  departments and a few subjects, no accounts. Never run the `dev` profile against the test
  database on `:5433` — it writes a row into that database's Flyway history and the next
  `./mvnw test` dies with `FlywayValidateException`. Repair:
  `DELETE FROM flyway_schema_history WHERE version IS NULL AND description='dev seed';`

---

## 2. Domain rules

### Core

1. **Eligibility window** is semesters `max(entrySemester,1)..currentSemester`
   (`EligibilityService`). No backlog is retired by moving up a year. `EligibilityService` stays
   total — it also handles an odd `currentSemester`, because an empty window would hard-block the
   student instead of flagging bad data.
2. **Semester parity applies to students only.** `currentSemester` ∈ {2,4,6,8}, `entrySemester` ∈
   {1,3,5,7}, +2 per step. Subjects, the timeline and cloning use the full 1..8 range — a semester-2
   student's backlogs are in semester 1, so narrowing a *subject* picker to even semesters hides
   half of every student's backlogs. Backend: `Semesters`; frontend: `lib/semesters.js`
   (`ALL_SEMESTERS` vs `CURRENT_SEMESTERS` vs `ENTRY_SEMESTERS` — picking the wrong list is the
   whole failure mode).
3. **Year binding fails closed.** A backlog semester resolves to the academic year the student first
   studied it (`student_semester_terms`, write-once per student+semester); subjects carry
   `academic_year_offered`. No row means "contact the department office" — never a guess.
4. **Academic year is one start-year integer** (`2025` = AY 2025-26) in the database and API.
   "2025-26" is display only (`lib/academicYear.js`).
5. **Course codes are free text.** Never derive a year from a code; the same code may recur across
   years (`UNIQUE(course_code, academic_year_offered)`).
6. **The studiable range is 1–8 everywhere.** `EligibilityService` returns an empty window above 8,
   so a wider range anywhere silently locks students out.

### Registrations

- **Verification is one-way; REJECTED is terminal.** `SUBMITTED → VERIFIED | REJECTED` and
  `VERIFIED → REJECTED`; anything else is 409. The status change and its audit event share one
  transaction.
- **Seeing is a union; verifying is the student's department.** A department can *see* a
  registration if it offers one of the subjects, is listed as eligible for one, or the student is
  its own. That rule exists in three places that must agree: `RegistrationSpecification.department`,
  `SubjectService`'s dropdown query and `AdminController.assertRegistrationInScope`. *Verifying* is
  narrower — only the student's own department (`RegistrationController.checkDeptAccess`), because
  the form is signed by the student's proctor and HOD. PROCTOR is scoped by assigned student
  everywhere. `canVerify` on each row must equal what `checkDeptAccess` enforces.
- **Registrations are immutable history** and snapshot student data. A referenced subject can't be
  deleted (409).
- `POST /api/register` carries only `subjectIds`. The printed "current semester" comes from the
  student record server-side, never the request.
- `GET /api/registration-status` is public and uses the same active-cycle check as registering.
- **Every timestamp is an `Instant` on a `timestamptz` column.** Anything printed goes through
  `Times.formatDate` in `Times.COLLEGE` (Asia/Kolkata) — never `ZoneId.systemDefault()`, which passes
  on any IST laptop and is wrong on a UTC server. To check no naive timestamp has crept back in:
  `select table_name, column_name from information_schema.columns where data_type='timestamp without time zone'`
  — only `flyway_schema_history.installed_on` should come back.

### Subject catalog

- **Printed fields freeze once registered.** A subject's name, course code, semester and credits
  can't change once any registration references it
  (`SubjectService.assertPrintedFieldsUnchangedOnceRegistered`, 409). Nothing snapshots them — the
  PDFs read them live, so an edit would rewrite forms already signed. The academic year is never
  editable (it is the binding key). Discontinue a subject by not cloning it forward.
- **CSV import takes year and department as batch fields, never columns.** A per-row year typo
  would create subjects that look right and are invisible to the students who need them.
- **Eligible departments travel as codes (`CS|CV`), never ids** — ids differ between databases.
  Pipe-separated because comma is the column separator.
- **`src/lib/csv.js` is the one CSV parser.** Never hand-roll `split(",")`: a quoted cell shifts
  every later field with no error.
- **An ELECTIVE must name at least one eligible department**
  (`SubjectService.assertElectiveNamesItsDepartments`). On update, an omitted list must not wipe the
  stored one.
- **Batch audit writes are best-effort** (`AdminAuditService.recordBestEffort`, used by subject
  clone and import): the rows are already committed one by one, so a failed audit insert must not
  500 the request and lose the result table. Never call it from a `@Transactional` method. A batch
  audit row records the outcome (created / skipped / errors), not just the request.

### Exam cycles

- `examMonthYear` is canonically `YYYY-MM`; "June 2026" is display, built in two places
  (`ExamMonths.format` for the PDF, `lib/examMonth.js` for the UI). Old free-text values are shown
  as-is. The picker is a month `<select>` + year input, because desktop Safari renders
  `<input type="month">` as a plain text box.
- **A cycle's name, month and batch list freeze once a registration references it** (409). There is
  no delete.
- The batch list printed on the form lives on the cycle (`exam_cycle_batch_lines`). Max 12 lines.
  Omitted on create = copy the previous cycle's list; an empty list = print no block.
  `ExamCycle.setBatchLines` copies its input because Hibernate mutates the collection in place.

### Scheduled reminders

- **ADMIN only.** The class-level `@PreAuthorize` on `ReminderController` is the only control,
  because `/api/admin/**` admits all five roles.
- **A reminder stores the audience rule, not a recipient list** (VERIFIED registrations in a cycle,
  optionally one department by USN branch). Recipients and their emails are resolved at send time.
- **Triggered by an external cron, never an in-process timer** (free hosts sleep).
  `POST /api/internal/reminders/run` is protected only by the `X-Cron-Token` header (≥ 32 chars,
  compared in constant time; unset = 404). It answers 202 at once and sends on a background thread.
- **The "nothing due" answer must not touch the database** (`ReminderRunner.quietUntil`, an
  in-memory cache), or the hourly ping keeps the database awake. Create/cancel invalidate it after
  commit (`invalidateAfterCommit`).
- **Delivery is at-least-once per student.** `reminder_recipients` UNIQUE(reminder_id, roll_no) is
  the resume point; a crash between Brevo accepting and the row being written can duplicate, never
  skip.
- **Failure handling** is in `ReminderRunner.process`; every path that stops a run must set
  `last_error`, or the reminder sits at "Sending" with no reason shown.
- Cancel works while SCHEDULED or SENDING; `@Version` settles cancel-vs-runner races. Reminders are
  never editable — cancel and recreate.
- `BrevoMailer` sends the body as `byte[]` so it has a Content-Length; a chunked body was rejected
  by Brevo in a live test.
- Brevo's free plan allows 300 emails/day; `MAIL_DAILY_CAP` (280) counts SENT rows in 24 h.
- Production email must go through Brevo's HTTPS API — Render's free tier blocks outbound SMTP.

### Student accounts

- Managed under `/admin/students`, scoped server-side by the USN's branch code. USN and DOB are not
  editable; DOB is write-only (it's the login credential) and reset via `POST /{rollNo}/reset-dob`.
  Delete only if unreferenced.
- **Email** is editable by staff and by the student (`service/Emails.java`, mirror `lib/email.js`).
  Default `<usn>@msrit.edu`; any valid address up to 254 chars; blank resets to the college address.
  A staff update that omits the field keeps the stored one.
- **The registration PDF always prints the college address** (`Emails.institutional(usn)`), never
  the student's edited email.
- **The student import never invents a semester.** A row without current/entry semester takes the
  batch default, and errors if that is missing too. (Entry semester used to default to 1, which is
  legal and so passed every check — a lateral entrant got a window from semester 1.)
- **Exactly two writers of `student_semester_terms`:** `seedLinearTimeline` (only on student
  create) and the audited `overrideProgression`. Never add a bulk timeline writer. Seeding is
  write-once; wrong years are corrected by hand on the student's Semesters panel.
- **Bulk progression** (ADMIN only) writes `students.current_semester` (+2) and its audit tables,
  never the timeline. Guards: no active exam cycle (409), every excluded USN must exist (400),
  `expectedCount` must still match (409, double-click guard). The audit is one `INSERT ... SELECT`
  in Postgres — rewriting it as an entity loop means ~20k round trips. The `UPDATE` must run last.
  In `StudentRepository`: keep `CAST(:p AS integer) IS NULL`, and use `lower(s.branch)` (never
  `upper`) because the index is on `lower(branch)`.
- The semester rules are also database CHECKs since V8 (`NOT VALID`, so legacy rows stay and are
  reported, never auto-corrected). A test that needs an invalid row must drop the constraints inside
  its own transaction (see `BulkProgressionSqlTest`).
- A student who ever had a backlog can never change branch (institutional rule): a transfer is a new
  USN, so the old row is always deletable.

### Staff accounts

- Usernames are renamable; role and department are not. `users.id` is the database key, but **the
  JWT subject is still the username**, so a rename signs that account out (intended).
- Self-rename: `POST /api/auth/change-username` (needs the current password). Renaming others:
  `PATCH /api/admin/users/{username}` (ADMIN only).
- History columns (`verified_by`, `actor`, `assigned_by`, …) keep the old name — they record who
  someone *was*.
- `DataSeeder` skips when any ADMIN exists, not by username — otherwise renaming the seeded admin
  would recreate a second admin on the next boot.
- Role ladder for creating accounts: PRINCIPAL → HOD, DEPT_OFFICE, PROCTOR; HOD → DEPT_OFFICE,
  PROCTOR in their own department.

### Proctors

- A proctor is department-limited and hard-scoped to an explicit set of assigned students
  (`proctor_students`, one proctor per student). Primitives in `ProctorScopeService`:
  `assignedRollNos` returns null only for "not a proctor, unrestricted"; `assertSupervises` 403s.
- A proctor can edit, progress, reset DOB, and view/verify registrations of their own students only.
  They cannot create, import or delete accounts, or write subjects, departments or users.
  "Delete" in the proctor UI means unassign.

### Departments

- Writes are ADMIN/PRINCIPAL only; HOD and DEPT_OFFICE are read-only there. Delete is 409 while
  anything references the department.
- **A department's code is fixed once it has students** (409) — it is part of every USN and decides
  who may verify.
- **A department with students but no HOD/DEPT_OFFICE is warned about, never blocked**
  (`hasVerifier`): setup must work in any order. `AdminController.VERIFYING_DEPT_ROLES` is the one
  definition of who verifies for a department; PROCTOR is deliberately excluded.

---

## 3. Backend conventions

### Schema and Flyway

- Flyway owns the schema; Hibernate only validates. Add the next `V<n>__description.sql` (check the
  current head; a duplicate number fails boot). Never edit an applied migration, never hand-apply
  DDL, never change a populated table through entity annotations. Put `NOT NULL`/unique/CHECK in a
  migration, not `nullable=false`.
- If every test reports `ApplicationContext failure threshold (1) exceeded`, the test database
  isn't running — the real error is a `Connection refused` to `localhost:5433` further up.
- `SPRING_PROFILES_ACTIVE=test ./mvnw spring-boot:run` silently uses the **main** config —
  `application-test.properties` is only on the test classpath. To run locally against the test
  database pass
  `-Dspring-boot.run.arguments="--spring.datasource.url=jdbc:postgresql://localhost:5433/backlog"`.

### Status codes

- **401** = no valid session (the frontend signs out). **403** = signed in but not allowed (shown in
  place; never sign out on 403).
- An unexpected database constraint violation is **500**. Only SQLSTATE `23505` (unique violation)
  becomes **409**. Classify by SQLSTATE, never by constraint name.
- Never map `IllegalArgumentException` to 400 centrally — `NumberFormatException` extends it, so
  real server bugs would be reported as client errors. Wrap at the call site.
- Id in the path → 404; id in the body → 400. Use `ResponseStatusException`.
- Registration refusals: 400 = the student can fix and resubmit; 409 = only staff can fix it.
- Creates 201, deletes 204 (except `/admin/users/{u}/reset`, which stays 200).
- Logout is never gated. Login never throttles and never returns 429 (a decision with the risk
  accepted — see `docs/adr/student-authentication.md`).

### Authorization

- Enforce every new admin endpoint server-side by role, department and (for proctors) assigned
  student. UI role lists are convenience only.
- Identity comes from the JWT, never the request body. Branch and admission year derive from the
  USN `1MS<YY><BR><NNN>`.
- `CallerScope` owns "who is calling?". Don't fold the per-controller `DEPT_ROLES` sets into it —
  they legitimately differ.
- A scope resolver returning `null` means only "unrestricted ADMIN/PRINCIPAL". Unknown caller ⇒ 401;
  department role without a department ⇒ 403.
- **Session revocation lives in `AccountExistenceFilter`:** it 401s when the account is gone, its
  role no longer matches the token, or the token predates `session_valid_from` (stamped on creation
  and on every credential or identity change). The token carries `iatMs` because the standard `iat`
  is in whole seconds.

### Batch endpoints

- Every bulk endpoint (subject import, student import, clone apply, proctor claim) refuses more than
  `Batches.MAX_ROWS` (500) rows with 400 before doing anything. Never truncate instead.
- Each row commits separately, so the loop must end with `catch (RuntimeException)`; otherwise an
  error leaves earlier rows committed while the request 500s with no result table. Catch order
  matters: `ResponseStatusException` before the catch-all, `NumberFormatException` before
  `IllegalArgumentException`. Log at ERROR.

### JPA fetching

- `open-in-view` is off. `Registration.subjects` is the only lazy association.
- Fetch-join to-one (`@EntityGraph`), batch-fetch to-many (`@BatchSize`). Never put a collection in
  an `@EntityGraph` on a paginated query — Hibernate drops the SQL `LIMIT` (`HHH000104`).
- `@EntityGraph` defaults to FETCH, which makes every unlisted attribute lazy. `findByRegId` uses
  `type = LOAD` on purpose.
- The registration list mapping must stay inside `RegistrationService.listSummaries`
  (`@Transactional(readOnly=true)`). `@Transactional` is proxy-based: calling it from the same class
  bypasses it.
- `@BatchSize` on `Registration.subjects` (1000) must exceed the max page size (200).
- `FetchStatementCountTest` guards this and must not be `@Transactional`.
- Entities whose `@Id` the code assigns (`Student`, `ProctorAssignment`) implement `Persistable`;
  otherwise `save()` merges and a create that loses a race silently overwrites.

### Writing tests that prove something

- **Mutation-test authorization and fetch tests:** break the guard, run, confirm exactly the
  expected cases fail, restore. Deleting a class-level `@PreAuthorize` often changes nothing because
  `SecurityConfig` already admits all five roles under `/api/admin/**`.
- **Where scoping is the point, assert content, not status** — the dangerous bugs answer 200.
- Authorization tests need the full context (`@SpringBootTest @AutoConfigureMockMvc
  @ActiveProfiles("test") @Transactional`) with a real `users` row per role, asserting exactly 403.
  Standalone MockMvc skips method security, so such a test passes regardless.
- `AdminAuthorizationFixture` is the shared cast of users — extend it. Never put registrations in
  it (they make students and subjects undeletable).
- `createStudent` is `REQUIRES_NEW`, so a successful create in a test commits real rows. Prove the
  allowed side by reaching a 400 instead.
- Mockito strict stubs throw on an unexpected call, which a batch catch-all then swallows as a row
  error. Stub with one `any()` matcher that branches inside.

---

## 4. Frontend conventions

### Theming and styling

- **`src/index.css` owns theming.** Read its token block before adding UI. Light/dark is
  `data-theme` on `<html>`. Use the token utilities (surfaces, ink, `border-stroke`, and three
  colours: `accent` = actionable, `success` = went through, `alert` = went wrong). Never a raw hex,
  never `x-[var(--token)]`, never colours in an inline `style={{}}` (dark mode can't override it).
- Tailwind v4 is CSS-first — there is no `tailwind.config.js`. Keep `@theme static`, the
  `@custom-variant dark (...)` line, and keep `index.css` unlayered. Each of these fails silently if
  changed.
- **Never build class strings as `` `${A}${B}` ``** — two classes fuse into one invalid token with
  no error. Use `[a, cond && b].filter(Boolean).join(" ")`.
- Never put two utilities for the same property on one element; they resolve by CSS source order.
- **`npm run lint:no-box` must pass:** no outlined boxes (`border border-stroke`, `rounded-xl border`,
  …) and no `hover:underline` standing in for a button. Separate with fill, spacing or a hairline.
- Never add a blanket `body *` colour rule — it once made every text colour inert.
- Check UI changes in both themes; no test checks styles.

### Components to reuse

- Buttons: `lib/buttonClasses.js` (`btn(tone, size)`). Form controls: `lib/formClasses.js`; labelled
  fields: `Field.jsx`; read-only pairs: `ReadOnlyField.jsx`.
- Layout and shared pieces: `AdminTabPage`, `AdminLayout`, `AdminSidebar`, `AdminPageShell`,
  `TopBand`, `PageLayout`, `CsvImportPanel`, `DepartmentOptions`, `Pager`, `AlertBanner`,
  `RouteFallback`.
- `AdminTabPage`'s tabs implement the full ARIA tab pattern — keep it whole.
- Page role gate: `hooks/useRoleGuard.js` (redirects to `/admin`). Role strings: `lib/roles.js` —
  never merge `DEPT_PINNED` and `DEPT_PINNED_NO_PROCTOR`.
- Two-step confirm: `hooks/useArmedConfirm.js` (a failed action must stay armed).
- `BulkProgressionTab` deliberately doesn't use `DepartmentOptions` — it needs codes, not ids.
- Every admin page opens with an `<h1>` and a one-sentence subtitle; lists of records are tables.
- `AdminPage.jsx` stays in `src/pages/` — `error-boundary.cy.js` intercepts that exact path.
- `TopBand`'s logo is an inlined SVG (so it can take the theme colour), not an `<img>`.
- StudentLoginPage's extra page height on touch devices fixes a dead-button bug in iOS Chrome —
  keep it.

### Async requests

- **Never write a reply to state without checking it is still the current request** — a slow reply
  otherwise lands after the inputs changed. `hooks/useAbortableRequest.js` is the primitive (ignore
  `ERR_CANCELED`); effect-driven fetches use an `ignore` flag + `AbortController`.
- Never clear the busy flag in a `finally` — it also runs for the superseded request.
- A failed re-fetch must clear the old rows (`null` = "not loaded", not `[]` = "nothing matched").
- `stale-results.cy.js` pins these cases with delayed stubs.

### Errors and loading

- `lib/loadError.js` decides whether a failure is worth showing. Never suppress the 401 in the axios
  interceptor.
- Coerce to an array only where the server may legitimately omit a field; otherwise a contract
  break becomes a silently empty table.
- A non-component export from a `.jsx` file adds a lint error — keep helpers in `.js` files.
- **Anything `App.jsx` imports loads on every cold page load.** Keep heavy imports (e.g. icon
  libraries) out of the entry graph. Check with
  `grep -o 'modulepreload[^>]*>' frontend/dist/index.html`.
- Redirects can't be tested in Cypress — import the pure decision function into a spec instead.

### Cypress

- **A spec landing on an admin page must stub every endpoint that page loads.** Vite proxies `/api`
  to `:8080`, so an unstubbed call 401s, signs the session out, and the spec fails intermittently on
  an unrelated assertion. `/api/admin/registrations*` does not cover `/summary-counts`.
- Never assert a layout width against the `cy.viewport()` number — Linux CI has classic scrollbars
  (~15 px narrower than macOS). Measure against `win.innerWidth`.
- Inside an `overflow-x-auto` table, `.should("be.visible")` on a `<td>` can fail; scope to `tr`.
- Don't remove the API stubbing — it is what lets the suite test error states. Backend behaviour is
  covered by the backend suite.

### Tooling

- Node is pinned by `frontend/.node-version` (26.7.0). `engines` (`^22.12.0 || >=24`) is the real
  floor — don't raise it to match the pin.
- Cypress's postinstall is allowed via `allowScripts` in `package.json`; removing it leaves e2e
  unrunnable after `npm ci`.

---

## 5. Hosting gotchas (free-tier Render + Neon)

- **Neon's free compute quota is easy to burn**, which suspends the database. Three things keep it
  safe: the keep-alive pings `GET /` (served from the jar, no database); `DB_POOL_MIN_IDLE=0` and an
  idle timeout under 5 minutes (an idle pooled connection counts as activity); and the reminder cron
  answering "nothing due" from memory. Neon's PgBouncer pooler isn't a fix — Flyway needs a session
  lock at boot.
- **The first request after idle is slow** (JVM boot + database resume). Any control that disables
  its own retry while a request is in flight will only misbehave here, never locally.
- **`APP_COOKIE_SECURE` defaults to true.** Over plain http (e.g. a phone hitting a LAN dev server)
  iOS Safari drops the cookie, so login appears to succeed and every request after is anonymous.
  macOS treats `localhost` as secure, which is why it only shows on a phone. Set
  `APP_COOKIE_SECURE=false` for that case only.
- **Cache headers are split** (`SpaStaticResourceConfig`): hashed `/assets/**` are cached for a
  year; `index.html` and every API response stay `no-store`. Caching `index.html` breaks deploys.
  `/assets/**` is its own handler so a missing chunk 404s instead of returning the HTML shell.
- Recovering an empty `users` table needs `ADMIN_PASSWORD_ADMIN` + a redeploy, or SQL.
- Testcontainers is not used. If it is ever added, versions ≤ 1.21.3 can't reach Docker 29.

---

## 6. Ideas already considered and rejected

These were tried or weighed and turned down. Re-open them only with a new reason.

1. **Value objects for `Semester`, `AcademicYear`, `Usn`, `Branch`** — ~40 call sites and the test
   setup can't verify the change safely; static helper classes instead.
2. **Bean Validation for context-dependent rules** — annotations check shape only; rules depending
   on the caller's role or preview-vs-commit stay in the service, where the authorization audit
   looks.
3. **A shared `classifyRowFailure(e)` helper for batch catch blocks** — `catch` order is checked by
   the compiler, an `instanceof` chain is not.
4. **"Subject import reveals whether a course code exists college-wide"** — accepted, not a
   security finding; it follows from course codes being unique college-wide.
5. **`table-fixed` layout on the registrations table, code-prefixed subject strings, "Sem"
   abbreviations, optimistic verify/reject** — tried and backed out.
6. **A client-side cache for departments / exam cycles** — staleness risk for little gain.
7. **Overloads that only default an argument** — they make a required argument look optional.
8. **A role check on `ProtectedAdminRoute`** — the role comes from the same client storage as the
   token, so it adds no security.
9. **Recomputing the seeded semester timeline when entry semester is edited, or flagging it as
   unconfirmed** — the seed-then-correct-by-hand design is intended.
10. **Storing bulk-progression audit as USN ranges** — a range points into mutable data and can't
    distinguish excluded, skipped or nonexistent.
11. **A migration rounding legacy odd semesters to even** — no rule recovers the truth; any guess
    would be baked into history.
12. **Login throttling / lockout / CAPTCHA** — risk accepted for the student surface; if ever
    needed it belongs at the hosting edge. See `docs/adr/student-authentication.md`.
13. **Enlarging touch targets to 44×44** — that's WCAG AAA; the AA requirement (24×24) is met.
14. **Extracting the duplicated page-size guard** — the duplication is known and accepted.
15. **Silencing Spring's generated-security-password warning** — it is inert (no form login or
    HTTP basic). If either is ever added, exclude `UserDetailsServiceAutoConfiguration` too.
16. **Asynchronous PDF generation** — needs a job store and delivery; on a host that can be killed
    any time, jobs would be lost after reporting success.
17. **Redis-backed HTTP sessions** — the app is deliberately stateless (JWT cookie).
18. **Rate-limiting `POST /api/register`** — already gated by a valid student session, an active
    cycle and one pending registration per student. PDF generation is the part that is rate-limited.
19. **Fixing iOS's date picker prefilling today's date** — not fixable without replacing the native
    control.
