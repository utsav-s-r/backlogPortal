# Product

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

Two audiences, prioritised **per surface** — neither outranks the other globally.

- **Students** (student surfaces: home, login, registration, dashboard). Engineering students with
  one or more backlog (failed/supplementary) subjects. They visit a few times a semester, sign in
  with USN + date of birth, pick the subjects they are eligible to re-attempt, and download a
  pre-filled PDF form. Many are on phones.
- **Staff** (admin surfaces). Five roles — ADMIN, PRINCIPAL (college-wide), HOD and DEPT_OFFICE
  (own department), PROCTOR (own department, assigned students only). During an exam cycle the
  department office and proctors verify or reject registrations against signed hard copies, often
  in volume; between cycles admins maintain subjects, student accounts and semester progression,
  departments, exam cycles, staff users and email reminders.

## Product Purpose

Digitises backlog-exam registration at Ramaiah Institute of Technology (MSRIT). It replaces a paper
process in which students hand-filled forms and staff re-keyed hundreds of them into a spreadsheet.
Success: a student registers only for subjects they are genuinely eligible for, prints a correct
form first time, and staff verify each one without re-typing anything.

## Positioning

Eligibility is computed server-side from the student's semester history, and each backlog is bound
to the academic year the student first studied that semester — so the form a student prints is
correct by construction, not by the student's own reading of the rules. The signed paper form stays
the authoritative artefact; the portal feeds and tracks it rather than replacing it.

## Operating Context

- Student journey: sign in → pick eligible subjects → download pre-filled PDF → get it signed by
  Proctor and HOD → hand it to the department office → it is verified and tracked centrally.
- Registration is open only while an exam cycle is active; the homepage shows open/closed.
- Staff work from a role-scoped admin dashboard: filter registrations, verify/reject, export PDFs,
  import CSVs, run bulk progression, schedule reminder emails to verified students.
- Sessions are a fixed one hour, not renewable.
- Hosted on free tiers (Render + Neon): the first request after idle is slow (cold JVM boot and
  database resume), so waiting states are a real part of the experience.

## Capabilities and Constraints

- React + Vite SPA served from a Spring Boot jar; PostgreSQL. Existing stack, not up for change.
- Light and dark themes; responsive down to phone width.
- **Performance is a top priority**: minimum loading and waiting, especially on poor connections.
  Optimisations must never break storage or caching behaviour (the existing cache split and session
  storage are load-bearing).
- Very low-end phones are explicitly not a target.
- Registrations are immutable history; verification is one-way (SUBMITTED → VERIFIED | REJECTED).
- Terminology: USN (university seat number, e.g. `1MS24CS001`), backlog, exam cycle, proctor,
  HOD, department office, academic year shown as "2025-26".

## Brand Commitments

- Name: "MSRIT Backlog Portal"; institution: Ramaiah Institute of Technology.
- Assets: MSRIT wordmark (`frontend/src/assets/MSRIT.svg`), logo/favicon
  (`frontend/public/MSRITLogo.svg`, `MSRITLogo.png`).
- Motion: no flashy animation; a little subtle motion here and there is fine (owner's stated
  preference).
- Voice (from existing copy): plain, instructional, second person — "Register for your backlog
  exam", "Hand the signed form to your department office".

## Evidence on Hand

- Standing: a **pilot / proposal** to the college, not yet the official process. Do not claim
  official adoption, usage numbers, or endorsements.
- No testimonials, statistics, or case studies exist; do not fabricate any.
- All data on the deployed instance is test data.

## Product Principles

1. **Correct by construction** — the system decides eligibility and fills the form; the user should
   never have to know the rules to get it right.
2. **The paper form is authoritative** — the portal supports the signed hard copy and its
   verification, never pretends to replace it.
3. **Fast above all** — every screen should load and respond with minimal waiting, including after
   a cold start.
4. **Serve the surface's own user** — student pages optimise for an occasional, possibly anxious
   phone user; admin pages for repeated, high-volume staff work.
5. **Quiet, not flashy** — clarity over decoration; motion only where it helps.

## Accessibility & Inclusion

No formal conformance level has been mandated (open decision). Touch targets have already been
audited against WCAG 2.2 SC 2.5.8 (AA) with zero failures; light/dark contrast is measured in
`docs/adr/ui-theming.md`.
