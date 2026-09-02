# ADR: Backlog progression & academic-year subject binding

Status: Implemented (Phases 0–5 + tests/docs) — 2026-06-13. Extended 2026-06-16 with
academic-year display format, course-code prefix enforcement, subject cloning, and the Manage
Subjects page (all committed on `rejectStatus`). 2026-06-29: the Add / Clone / Manage subject UIs
were consolidated into one tabbed page at `/admin/manage-subjects` (`?tab=manage|add|clone`); the
standalone `AddSubjectPage`/`CloneSubjectsPage` and their `/admin/add-subject` + `/admin/clone-subjects`
routes were removed. Pure UI refactor — endpoints/services unchanged.
2026-09-02: **course-code prefix enforcement REMOVED** (the institutional premise was wrong — see
that section); course codes are now free text. `academicYearOffered` and year-binding unchanged.
Same day: **subject CSV import** added as a fourth tab (`?tab=import`) — the first-load bulk path
clone cannot serve. Year and department are batch-level, never columns.
Branch: `rejectStatus` (committed)

Resolved (2026-06-16): `subjects.year_of_joining` retired (entity field removed; physical
`DROP COLUMN` applied on Neon) and issue #7 closed — `AddSubjectPage` relabeled to "Academic
Year Offered" with a corrected year range. Follow-up: `subjects.academic_year_offered` is now
enforced NOT NULL via a hand-applied migration (2026-06-16, since folded into `V1__baseline.sql`),
matching the schema to the entity's primitive-int mapping.

## Context

Students register for backlog (re-exam) subjects. Two rules were unenforced:

1. **Which semesters** a student may register backlogs for, given their current semester.
2. **Which version** of a semester's subject list applies — subject lists for the same
   `(semester, branch)` change between academic years (syllabus revisions), and a student
   retaking a backlog must see the version from the year they actually studied that semester.

Before this work the student free-picked any year + any semester on the registration page
and the server validated only branch/elective membership of the chosen subjects
(`RegistrationService.register`). Subjects were served by exact match on an overloaded
`Subject.yearOfJoining` (`SubjectController`).

## Decisions

- **Binding model: academic-year-of-attendance.** Each backlog semester resolves to the
  academic year (AY) the student *first studied* that semester. Formulas break under
  year-backs/detentions, so an explicit per-student progression record is the source of truth.
- **Eligibility: computed from `Student.currentSemester`**, which is admin-maintained each
  term and held back on detention.
- **Retake version: "year they first studied it"** → progression rows are write-once.
- **Electives bind identically** to regular subjects, by `academicYearOffered`.
- **Progression maintenance: both** a "Promote Batch" admin UI and a CSV import, sharing one
  service method.
- **Backfill** of students already mid-degree: linear-default seeding + manual correction.

## Eligibility window (closed form)

```
eligible = { max(entrySemester, 1) .. currentSem }
```

For a normal intake (`entrySemester = 1`, the default for every existing student):

| currentSem | eligible      |
|------------|---------------|
| 2          | {1,2}         |
| 4          | {1,2,3,4}     |
| 6          | {1,..,6}      |
| 8          | {1,..,8}      |

Rationale: a backlog is cleared or it isn't — moving up a year does not make it go away. Every
semester the student has actually studied here stays registrable until they pass it.

**Lateral entry (2026-06-29):** `Student.entrySemester` (int, default 1; `>1` = a migrant who joined
mid-degree) is the floor, so e.g. a transfer at `entrySemester=3`, `currentSem=6` sees `{3,4,5,6}` —
never the sems 1–2 they didn't study here.

**Superseded (2026-08-17):** the window used to RETIRE old backlogs —
`floor = max(currentSem <= 4 ? 1 : currentSem <= 6 ? 3 : 5, entrySemester)`, so a sem-8 student saw
only `{5,6,7,8}` and a first-year backlog fell permanently out of reach. Owner decision: a student
may register any semester from entry through current. Consequence to watch: the subject catalog must
carry the old years' offerings, or year-binding fails closed and the student is told to contact the
department office. `EligibilityService` is unchanged in shape — still the pure 3-arg
`isEligible(current, entry, target)`, still consumed by `RegistrationService.register` and
`StudentController`.

## Semester parity (2026-08-17)

An academic year is a semester **pair**, so:

- `Student.currentSemester` ∈ **{2, 4, 6, 8}** — where a student sits
- `Student.entrySemester` ∈ **{1, 3, 5, 7}** — where a student joined (entry is at a year boundary)
- progression moves **+2**, which preserves the parity by construction

**Parity is narrower than the studiable range and applies to `Student` ONLY.** `Semesters.MIN/MAX`
stays 1..8 and governs `Subject.semester`, `StudentSemesterTerm.semester`, cloning and the
eligibility window — a sem-2 student's backlogs are in sem 1, so odd semesters must remain
registrable. Applying parity to subject pickers would silently hide half of every student's
backlogs. Enforced by `Semesters.assertCurrentSemester` / `assertEntrySemester`, called from the
single write path `StudentManagementService.validateSemesters` (create, update and import all route
through it); mirrored in the UI by `frontend/src/lib/semesters.js`.

Legacy rows with an odd `currentSemester` or even `entrySemester` are **reported, never guessed at**:
bulk progression skips them as `SKIPPED_INVALID_SEMESTER`, and `EligibilityService` deliberately
still resolves them (an empty window would hard-block the student rather than flag the data).

## Bulk progression (2026-08-17)

Year-end promotion of a whole cohort: `current_semester + 2` for everyone in the selection, minus an
admin-supplied exclusion list. **ADMIN only** — `hasRole('ADMIN')`, which excludes PRINCIPAL and
every dept role; this is an institution-wide write.

**It writes `students.current_semester` and its own audit tables, and must NEVER touch
`student_semester_terms`.** The timeline keeps exactly two writers (`backfillLinear` at creation,
`overrideProgression` for a hand correction) — a third is what made progression disagreement
possible in the deleted bulk tools. A detained student's *years* are corrected on the per-student
Semesters panel; bulk progression only moves the integer.

Cost is **4 statements regardless of cohort size** (~20k students): count, `INSERT ... SELECT` the
promoted audit, `INSERT ... SELECT` the not-promoted audit, then one bulk `UPDATE`. The audit rows
are built inside Postgres and never materialise in Java — an entity loop would be ~40k network round
trips to Neon. The UPDATE runs **last**, so `semester_from` captures the pre-state.

Four guards, all server-side (`BulkProgressionService`):

| Guard | Status | Why |
|---|---|---|
| No active exam cycle | 409 | A wrong promotion is reversible; a registration made under it is not (immutable history). This is the only unrecoverable failure mode. |
| Every excluded USN is a real student | 400 | A typo'd exclusion silently promotes someone meant to be held back — the exact failure the list exists to prevent. The whole request fails, naming the offender. |
| `expectedCount` still matches | 409 | The double-click guard: after a successful run the candidate set has changed, so a stale confirmation can't re-fire. |
| Audit count == update count | rollback | The audit would otherwise be a lie. |

Audit lives in `progression_batches` + `progression_batch_students` (V9), written in the **same
transaction** as the UPDATE — the `registration_events` precedent. Deliberately NOT `log.info`: the
project has no logging configuration at all, so application logs are console-only and retain
nothing, which is fine for a one-student override and not for a 20k-row write. `roll_no` is a plain
column, not an FK, so the audit outlives the student.

## Data model

| Entity | Change |
|---|---|
| `Subject` | new `academicYearOffered: int` (`2023` = AY 2023-24; odd sems = fall term, even = spring). Offering = `(academicYearOffered, semester, department, courseCode)`. New years = new rows; old rows kept forever. Replaces overloaded `yearOfJoining`. |
| `StudentSemesterTerm` (**new**) | `rollNo` (FK), `semester` (1-8), `academicYear` (int). PK `(rollNo, semester)`. Write-once = "first studied". |
| `Student` | `currentSemester` becomes authoritative (no schema change). Later: new `entrySemester: int` (default 1; raises the eligibility floor for lateral-entry students — DB `NOT NULL DEFAULT 1` via a hand-applied migration of 2026-06-29, since folded into `V1__baseline.sql`). |
| `ExamCycle` | new `academicYear: int`, `term: ODD\|EVEN`. |
| `Registration` | optional `snapAcademicYear` (form immutability, Phase 4). |

Fail-closed: if an eligible semester has no progression row, the student gets a clear
"contact dept office" message — never a guessed year.

## Phased implementation

- **Phase 0 — Schema foundations.** Additive columns/tables, no behavior change. Add
  `subjects.academic_year_offered` as a NEW column (never rename `year_of_joining` —
  `ddl-auto` would drop it) and backfill; create `student_semester_term`; add
  `exam_cycles.academic_year` + `term`.
- **Phase 1 — Eligibility window** (independently shippable). `EligibilityService`; expose
  eligible sems on `/student/me`; constrain the `RegistrationPage` semester dropdown; reject
  out-of-window subjects in `RegistrationService.register`.
- **Phase 2 — Progression + AY binding (read path).** Rewrite `GET /api/subjects` to drop
  client `year`, resolve AY from `StudentSemesterTerm`, fail closed. Drop the year dropdown;
  show resolved AY read-only.
- **Phase 3 — Progression maintenance.** Shared
  `ProgressionService.recordProgression(rollNo, semester, academicYear)` (insert-if-absent,
  advance currentSemester); Promote Batch UI (cohort + target sem + AY, preview, detention
  hold, dept-scoped); CSV import (idempotent, dry-run + error report); linear-default backfill
  seeder; single-row override for corrections (audited).
  **Reduced 2026-08-16:** all of Phase 3 except the backfill seeder and the audited single-row
  override was deleted. Once seeding at creation became total, the bulk tools could only ever report
  "already recorded" — `recordProgression` was insert-if-absent onto rows that always exist, and the
  gaps sweep scanned a range already fully written. What remains: `backfillLinear` (called only from
  `createStudent`), `overrideProgression`, and `GET`/`PUT /api/admin/progression/{rollNo}...`. The
  `CONFLICT` outcome went with them — it required two writers disagreeing, and there is now one
  creator and one deliberate overwriter.
- **Phase 4 — Submit hardening + snapshot.** Assert
  `subject.academicYearOffered == StudentSemesterTerm[student, subject.semester]`; add
  `snapAcademicYear`.
- **Phase 5 — Cleanup/tests/docs.** Retire `year_of_joining`; relabel `AddSubjectPage` field
  + fix year-range (known issue #7); unit/integration/Cypress tests (closes part of #17).

Dependency chain: Phase 0 → (1 ∥ 2-foundations) → 2 → 3 → 4 → 5. Phase 1 is shippable today.

## Academic-year representation

Stored canonically as a single **start-year `int`** (2025 = AY 2025-26) in every column
(`student_semester_terms.academic_year`, `subjects.academic_year_offered`,
`exam_cycles.academic_year`, `registrations.snap_academic_year`) and over the API — the
`-26` is always `start+1`, so it is pure presentation. The int stays the source of truth for
all comparisons, the year-binding equality check, and the `(course_code, academic_year_offered)`
unique key. Human-facing `YYYY-YY` formatting/parsing lives only in the frontend
(`frontend/src/lib/academicYear.js`), applied at the display + input edges (RegistrationPage,
AddSubjectPage, ManageProgressionPage). Inputs stay parse-tolerant of a bare `2025` too.

Course-code prefix — **REMOVED 2026-09-02 (owner: the institutional premise was wrong).** The rule
held from 2026-06-16 to 2026-09-02: a course code's first two digits were the academic-year start
(`22CSL44` → 2022), the year stamped a locked prefix the admin could not edit, and
`SubjectService` rejected a mismatch on create and edit. The college does not in fact number courses
that way, so the invariant was enforcing a fiction — a real catalog could not be entered.

**A course code is now free text.** It carries no year information, recurs unchanged across years,
and is validated for nothing but presence. `CourseCodes.java`, `CourseCodeField.jsx`, and the
`academicYear.js` prefix helpers (`academicYearPrefix`/`courseCodeSuffix`/`buildCourseCode`) are
deleted; `formatAcademicYear`/`parseAcademicYear` remain, since the `YYYY-YY` display format is a
separate concern and unaffected.

What did NOT change, and why the removal was safe to make subtractively:
- **`academicYearOffered` is untouched** and remains the binding key. Removing the prefix rule
  removes a *derivation* of the year from the code; it does not remove the year, which was always
  the authoritative int (the prefix was the redundant copy).
- **`UNIQUE(course_code, academic_year_offered)` already permitted code reuse across years**, so no
  migration was needed. The prefix rule had made codes incidentally year-unique, meaning that
  composite index was never exercised as designed; now it is.
- **No query keys on course code alone** (`SubjectRepository` has no `findByCourseCode`), so codes
  repeating across years introduces no ambiguity anywhere.

The app-layer-not-DB-CHECK reasoning from the original decision is retained for the rules that
remain: naming conventions stay cheaply relaxable, which is exactly what let this one be removed
without touching the frozen V1–V4 migrations.

## Subject CSV import (first load)

Added 2026-09-02. Clone only helps once a previous year's catalog exists, so a first-time load —
and the historical years an existing student's backlogs resolve into — had no bulk path at all;
subjects could only be added one at a time. `POST /api/admin/subjects/import`
(`SubjectController` + `SubjectImportService`) takes parsed CSV rows plus a **batch-level**
`academicYearOffered` and `deptId`, both stamped on every row server-side.

**The year is deliberately NOT a per-row column**, unlike the student importer's per-row semester.
Students genuinely need theirs (a real cohort mixes lateral-entry semesters); subjects have no
equivalent forcing case, and the closer precedent is `SubjectCloneService` forcing its `targetYear`.
The asymmetry decides it: year-binding is fail-closed, so a mistyped year produces a subject that
looks correct in the admin catalog and is invisible to every student who needs it. Typed once per
file, it is checked once. Adding an optional per-row column later stays backward-compatible;
removing one would not.

Eligible departments arrive as **codes** (`CS|CV`), not ids: ids are `@GeneratedValue` and differ
between the dev and `publicProduction` branches, so a file authored against one would import
silently wrong against the other. Pipe-separated, because a comma is the column separator.

A dept-pinned caller who names another department is **403, not silently redirected** — a redirect
answers 200 and reports rows created somewhere the admin did not choose. Mutation-tested.

The row loop is non-transactional (each create commits on its own) and therefore carries the house
catch ladder ending in `catch (RuntimeException)`; a dry run resolves department codes too, so a
preview cannot promise a `WOULD_CREATE` that the real run turns into an `ERROR`.

## Subject cloning (year rollover)

Setting up a new year's offerings is a clone, not a re-entry: `SubjectCloneController`
(`/api/admin/subjects/clone/preview` + `/apply`) + `SubjectCloneService` copy a department's
subjects from a source year into a target year, **copying the course code verbatim and moving only
`academicYearOffered`** to the target (before 2026-09-02 the code's two-digit prefix was bumped too;
with the prefix rule gone a course simply keeps its code, and the `(code, year)` uniqueness is what
separates the two offerings).
Two phases: *preview* generates an editable draft (per-row `WOULD_CREATE` / `WOULD_SKIP`, or
`ERROR` for a source with no course code — apply refuses those, so preview must not promise them);
*apply* commits the admin-approved rows with **skip-existing** (the
`UNIQUE(course_code, academic_year_offered)` index is the backstop), so it's idempotent /
re-runnable. Each create runs in its own transaction so one bad row can't poison the batch.
Offerings are intentionally **independent flat rows** — no canonical-subject/offering split,
because name/code/credits all drift year to year (see the data-model discussion); the clone is
a starting template you edit, not a relational link.

Scope: **DEPT_OFFICE + HOD → own department; ADMIN + PRINCIPAL → any** (server-enforced via
`resolveDept`, mirroring `ProgressionController`). This expanded subject-creation rights: HOD and
PRINCIPAL now also get the single-subject `AddSubject` flow, and `addSubject` (moved from
`AdminController` into `SubjectController` on 2026-09-02, same URL) now
enforces dept scope server-side (previously only pinned in the UI). The UI is the dept-scoped
**Clone tab** (`/admin/manage-subjects?tab=clone`, `CloneSubjectsTab`): department + source/target
year (span format) + semester selector (all default, odd/even/none shortcuts) → editable preview
grid → apply.

Maintenance — `SubjectController` (`GET/PUT/DELETE /api/admin/subjects`) + the **Manage tab**
(`/admin/manage-subjects?tab=manage`, `ManageTab`): list/filter the catalog (dept/year/semester) and **edit** or **delete**.
Edit changes name/credits/semester/type/eligibility and the course code (free text since
2026-09-02); **`academic_year_offered` is denied** (it's the binding key) and
the dept can't be reassigned. **Delete is blocked (409) when any registration references the
subject** (`RegistrationRepository.existsBySubjects_Id`) — registrations are immutable history, so a
referenced subject is never deletable; discontinuation is handled by simply not cloning it forward.
Same dept-scoping. No soft-delete/archive flag (deliberately — "don't clone next year" covers it).

## Student account management (2026-06-29)

Admins create/manage student records (previously out-of-band). Standalone tabbed page
`/admin/students` (`?tab=manage|add|import`, shell `src/pages/students/StudentsPage.jsx`) →
`StudentManagementController` (`/api/admin/students`) + `StudentManagementService`. Dept-scoped by USN
branch code exactly like `ProgressionController` (ADMIN/PRINCIPAL broad; HOD/DEPT_OFFICE own-dept).
- **Create / edit / import:** create one, edit (name/email/phone/currentSemester[warns]/entrySemester
  — **USN and DOB are not editable here**), bulk CSV import (dry-run + per-row, batch-default
  semesters, skip-existing). `1 ≤ entrySemester ≤ currentSemester ≤ 8` enforced.
- **DOB is the login credential → write-only:** never returned by any endpoint (`StudentSummaryResponse`
  omits it); set at create, corrected via `POST /{rollNo}/reset-dob`.
- **Delete only if unreferenced** (409 via `RegistrationRepository.existsByStudent_RollNo`) — same
  immutable-history rule as subjects.
- **Progression IS auto-seeded on create** (superseded 2026-07-06). The original decision was the
  opposite — "no fabricated years; wrong years fail silently, missing years fail loud" — backed by a
  post-create warning, a "gaps" filter, and an incomplete badge. Full seeding replaced all three:
  `createStudent` calls `backfillLinear`, stamping `entrySemester..8` linearly from the admission
  year. The trade-off, accepted knowingly: a seeded year is indistinguishable
  from a verified one, so "contact the department office" can no longer fire for a portal-created
  student, and the no-detention assumption is wrong for exactly the population registering backlogs.
  Correction is per-student, via the Semesters panel on the Manage tab.
- All sensitive writes (create/update/delete/DOB-reset) are audit-logged (mirrors `PROGRESSION_OVERRIDE`).

## Cross-cutting

- Auth scope mirrors user-management: ADMIN/PRINCIPAL broad, HOD/DEPT_OFFICE own-dept.
- Within a phase: ship read/UI before submit-hardening so the UI is compliant before the
  server starts rejecting.
- Don't reintroduce the N+1 just fixed (`@EntityGraph` on `RegistrationRepository.findAll`).
  Fetch strategy, `open-in-view=false`, and the transaction boundary the admin list depends on
  are now specified in `docs/adr/persistence-fetching.md` — read it before changing any query.
- Biggest risk: backfill accuracy for detained students — linear default + correction + fail-closed.
