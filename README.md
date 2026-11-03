# Backlog Registration Portal

A full-stack web app that digitises student registration for backlog (supplementary) examinations at
an engineering college. Students sign in, pick the subjects they're eligible to re-attempt, and
download a pre-filled PDF form; departmental staff verify the signed hard copy from a role-scoped
admin dashboard.

Replaces a paper process where students hand-filled forms and staff re-keyed hundreds of them into a
spreadsheet.

- **Source:** <https://github.com/utsav-s-r/backlogPortal>
- **Live deployment:** <https://backlogportal.onrender.com>

> **Taking over this project?** After this README, read
> [`docs/MAINTAINER_NOTES.md`](docs/MAINTAINER_NOTES.md): domain rules, traps that fail silently,
> code conventions, how to set up accounts and test data, and ideas already tried and rejected.
>
> Some facts in this README and in the notes may be outdated. Where something can be verified
> against the code (schema, environment variables, limits, roles), check the code — it is the
> source of truth.

## How it works

**Student** — signs in with USN and date of birth, sees only the subjects they're eligible for,
registers while an exam cycle is open, downloads the pre-filled PDF, and submits the signed copy to
the department office.

**Staff** — browse and filter registrations, verify or reject them against the hard copy, and
maintain the data behind the process: subject catalog, student accounts and semester progression,
departments, exam cycles, staff users and scheduled reminder emails.

## Features

- Eligibility computed server-side; subjects are bound to the academic year the student first
  studied that semester.
- Registrations are immutable history — `SUBMITTED → VERIFIED | REJECTED`, with an audit trail.
- Server-side PDF generation for the student form and bulk staff exports.
- CSV import and year-to-year cloning for subjects; CSV import and audited bulk progression for
  student accounts.
- Scheduled reminder emails to verified students of an exam cycle (Brevo HTTPS API).
- Five staff roles with department and per-student scoping, enforced server-side on every endpoint.
- Light/dark theming, responsive to mobile, one-hour non-renewable sessions.

## Roles

| Role | Scope |
| :--- | :--- |
| `ADMIN` | College-wide, including bulk progression, exam cycles, reminders and staff renames |
| `PRINCIPAL` | College-wide |
| `HOD` | Own department, plus creating `DEPT_OFFICE`/`PROCTOR` users |
| `DEPT_OFFICE` | Own department |
| `PROCTOR` | Own department, and only an explicitly assigned set of students |

Students are not staff users: they live in the `students` table and sign in with USN + date of birth.

## Stack

| Area | Technology |
| :--- | :--- |
| Backend | Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security, Flyway, iText |
| Frontend | React 19, Vite 8, React Router 7, Tailwind CSS 4, axios |
| Database | PostgreSQL 18 (Neon in production) |
| Hosting | Render (one Docker container), Upstash Redis, Brevo, cron-job.org |
| Testing | JUnit 5 + Mockito (backend), Cypress (end-to-end) |

## Repository layout

| Path | What it is |
| :--- | :--- |
| `backend/backlog/` | Spring Boot app, package root `com.college.backlog` |
| `backend/backlog/src/main/resources/db/migration/` | Flyway migrations — the only way the schema changes |
| `frontend/` | React SPA (Vite); built into the jar for production |
| `docs/DATABASE.md` | Database schema reference, backups and the schema drift check |
| `docs/MAINTAINER_NOTES.md` | Working knowledge for maintainers — rules, traps, conventions, rejected ideas |
| `docs/adr/` | Architecture decision records — read before changing the related area |
| `Dockerfile` | Production image: builds the SPA, bundles it into the jar |
| `docker-compose.yml` | Local run of the production image + a throwaway Postgres |
| `.github/workflows/ci.yml` | CI: backend tests, frontend build/lint, Cypress, dependency audit |

## Setup (local development)

Needs **JDK 21**, **Node ≥ 22.12** (pinned to 26.7.0 in `frontend/.node-version`), and
**PostgreSQL 18**. Maven comes from the wrapper.

Two files are gitignored; create them from their tracked templates:

```bash
cd backend/backlog
cp src/main/resources/application.properties.example src/main/resources/application.properties
cp .env.example .env
```

Then set in `.env` (see [Environment variables](#environment-variables) for the full list):

- `DB_URL` (JDBC spelling: `jdbc:postgresql://host:5432/db?sslmode=require`, no `user:pass@`),
  `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET` — 32 bytes minimum (`openssl rand -base64 48`)
- `ADMIN_PASSWORD_ADMIN` — seeds the bootstrap `admin` account on a fresh database; every other
  account is created from Manage Users once signed in
- `APP_COOKIE_SECURE=false` only if you open the app over plain http from another device

> `.env` is loaded by `application.properties`. If it points at the production database, an
> unqualified `./mvnw spring-boot:run` writes to production. Point it at a local or development
> database while developing.

Run:

```bash
cd backend/backlog && ./mvnw spring-boot:run   # :8080, Flyway builds the schema on first boot
cd frontend && npm install && npm run dev      # :5173, proxies /api to :8080
```

The SPA is bundled into the jar in production, so one container serves both the app and the API:

```bash
cp compose.env.example .env    # set JWT_SECRET and ADMIN_PASSWORD_ADMIN
docker compose up --build      # :8081
```

## Testing

The backend suite runs the Flyway migrations and validates the schema, so it needs a database on
`localhost:5433`:

```bash
docker run -d --name backlog-test -e POSTGRES_USER=verify -e POSTGRES_PASSWORD=verify \
  -e POSTGRES_DB=backlog -p 5433:5432 postgres:18
cd backend/backlog && ./mvnw test
```

```bash
cd frontend && npm run test:e2e   # Cypress; specs stub their API calls, no backend needed
cd frontend && npm run lint:count # lint has 9 known, intentional errors; this fails on any other count
cd frontend && npm run lint:no-box
```

**The tests never send real email.** The backend tests read the same `.env` as the app, so
`src/test/resources/application-test.properties` pins `app.mail.brevo-api-key` and `app.mail.from`
to empty: tests always run with email switched off, even if your `.env` holds a real Brevo key and
sender for trying reminders locally. Keep those two lines. Without them, a configured `.env` makes
`ReminderAuthorizationTest` call Brevo for real. For the same reason, use `example.com` addresses
(reserved, never anyone's inbox) in any test that could reach the mailer. The other mail tests mock
the mailer or use a local fake server.

In CI, the **Dependency scan** job has a step that shows "Process completed with exit code 1".
That is expected: it audits the full dependency tree for information only (`continue-on-error`)
and fails on dev-only advisories from Vite, Cypress and ESLint. The step that actually gates the
build is `npm audit --omit=dev`, which must stay clean. Check the job's overall result, and don't
silence the informational step with `|| true`.

If every backend test fails with `ApplicationContext failure threshold (1) exceeded`, the test
database isn't running — check `docker ps` before debugging Java.

## Deployment (Render)

| Piece | Service | Notes |
| :--- | :--- | :--- |
| App + API | Render web service, free tier, built from `Dockerfile` | Cold start after idle is ~2 min (JVM boot) |
| Database | Neon PostgreSQL (its own production branch) | Autosuspends after 5 min idle |
| PDF rate limit | Upstash Redis | Optional; the app fails open without it |
| Email | Brevo HTTPS API | Render's free tier blocks outbound SMTP, so SMTP cannot work |
| Keep-alive (optional) | cron-job.org job: `GET /` every 14 minutes | See [Render free tier: sleep and cold starts](#render-free-tier-sleep-and-cold-starts) |
| Reminder trigger | cron-job.org job: hourly `POST /api/internal/reminders/run` with header `X-Cron-Token: <CRON_TOKEN>`, empty body | Separate job from the keep-alive. Healthy = 202 on the first ping after a boot, 204 after |

Flyway runs on every boot, so deploying a new migration is just a deploy. To take over:

1. Create your own Neon project/branch and copy its JDBC URL, user and password.
2. Create a Render web service from this repository (Docker runtime, repo-root `Dockerfile`).
3. Set the Render environment variables below. Set `ADMIN_PASSWORD_ADMIN` for the **first** boot
   only, sign in as `admin`, create the real accounts, then remove the variable.
4. Optionally create Upstash Redis and a Brevo account (verify a sender address), then add the two
   cron-job.org jobs above pointing at your Render URL.

### Render free tier: sleep and cold starts

On Render's free tier the service goes to sleep after 15 minutes without traffic. The next request
then waits for a cold start (about 2 minutes here, mostly JVM boot), and Neon may also need a moment
to resume.

**Optional: keeping the instance awake (hobby/showcase use only).** For testing or demos you can
use an external pinger to avoid the cold start:

1. Create a free account on a service such as [cron-job.org](https://cron-job.org/).
2. Set up a job that sends an HTTP `GET` to your application's `/` URL. Use `/` only: it is served
   from the jar without touching the database, so Neon can still suspend. Pointing it at an `/api`
   endpoint would keep the database awake and use up Neon's free compute quota.
3. Schedule the ping every 14 minutes.

Check Render's current free-tier terms before relying on this; for real production use, a paid
instance that does not sleep is the proper fix.

The reminder cron is a separate job and is needed whether or not you use a keep-alive pinger.

### Environment variables

Every value the backend reads, from `application.properties`. Set them on Render under
**Environment**; never commit real values. Nothing in the frontend needs environment variables.

**Required on Render**

| Variable | Purpose |
| :--- | :--- |
| `DB_URL` | JDBC URL, e.g. `jdbc:postgresql://<host>/<db>?sslmode=require&channelBinding=require` (no credentials in it) |
| `DB_USER` | Database user |
| `DB_PASSWORD` | Database password |
| `JWT_SECRET` | Session signing key, ≥ 32 bytes, or boot fails. `openssl rand -base64 48` |
| `DB_POOL_MIN_IDLE` | Keep `0` — an idle pooled connection stops Neon from suspending and burns the free quota |
| `DB_POOL_IDLE_TIMEOUT_MS` | Keep below 5 min (e.g. `30000`) for the same reason |
| `FLYWAY_CONNECT_RETRIES` | e.g. `10` — rides out Neon waking up at boot (default 0) |

**First boot only**

| Variable | Purpose |
| :--- | :--- |
| `ADMIN_PASSWORD_ADMIN` | Creates the `admin` account if no ADMIN exists. Remove after first sign-in |

**Optional features**

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `RATELIMIT_ENABLED` | `false` | Turns on the PDF download limit |
| `SPRING_DATA_REDIS_URL` | — | `rediss://default:<password>@<host>.upstash.io:6379`, needed when rate limiting is on |
| `RATELIMIT_PDF_MAX` | `10` | PDFs per window per user |
| `RATELIMIT_PDF_WINDOW_MINUTES` | `60` | Window length |
| `BREVO_API_KEY` | — | Brevo v3 API key (`xkeysib-…`). Unset = reminders off |
| `MAIL_FROM` | — | Sender address verified in Brevo |
| `MAIL_FROM_NAME` | `MSRIT Backlog Portal` | Sender display name |
| `MAIL_DAILY_CAP` | `280` | Sends per rolling 24 h (Brevo free = 300/day) |
| `CRON_TOKEN` | — | Shared secret for the reminder cron, ≥ 32 chars (`openssl rand -hex 32`). Unset = endpoint 404s |

**Tuning (defaults are correct; change only with a reason)**

| Variable | Default | Purpose |
| :--- | :--- | :--- |
| `PORT` | `8080` | Render sets this itself |
| `APP_CORS_ALLOWED_ORIGINS` | localhost:5173/5174 | Only matters if the SPA is served from another origin |
| `JWT_EXPIRATION_MS` | `3600000` | Session length (1 h, non-renewable) |
| `APP_COOKIE_SECURE` | `true` | Must stay `true` over https |
| `APP_COOKIE_SAME_SITE` | `Lax` | `Lax` / `Strict` / `None` (validated at boot; `None` requires Secure) |
| `DB_POOL_MAX_SIZE` | `5` | Hikari pool size |
| `DB_CONNECTION_TIMEOUT_MS` | `30000` | Hikari connection timeout |
| `FLYWAY_CONNECT_RETRIES_INTERVAL` | `2` | Seconds between Flyway retries |
| `FORWARD_HEADERS_STRATEGY` | `framework` | Trusts Render's `X-Forwarded-*` (needed for HSTS) |
| `APP_MAX_REQUEST_BYTES` | `8388608` | Request body cap (8 MiB) |
| `JPA_DDL_AUTO` | `validate` | Keep `validate` — Flyway owns the schema |
| `JPA_OPEN_IN_VIEW` | `false` | Keep `false` |
| `JPA_SHOW_SQL` / `JPA_FORMAT_SQL` | `false` | SQL logging, debugging only |
| `SQL_INIT_MODE` | `never` | Keep `never` |

## Handoff checklist

- [ ] Set up your own GitHub repo (fork or copy), Render service, Neon project, Upstash, Brevo and
      cron-job.org accounts.
- [ ] Set the Render environment variables above with **new** secrets (`JWT_SECRET`, `CRON_TOKEN`,
      database password, Brevo key). Rotating `JWT_SECRET` signs everyone out, which is harmless.
- [ ] Sign in as `admin`, change its password, and create your own staff accounts.
- [ ] For reminder emails, create your own Brevo account, verify a sender address (ideally an
      official college address), and set it as `MAIL_FROM` with your own `BREVO_API_KEY`.
- [ ] Set up your own database. The live deployment linked at the top is for reference only and
      holds test data.
- [ ] Read [`docs/MAINTAINER_NOTES.md`](docs/MAINTAINER_NOTES.md) before changing code.
- [ ] Run the backend tests and Cypress locally once to confirm your environment works.

## Database

PostgreSQL, with the schema owned by Flyway migrations in
`backend/backlog/src/main/resources/db/migration/`. Never edit an applied migration or hand-apply
DDL; every change is a new `V<next>__description.sql`.

The full table-by-table reference (columns, types, constraints, indexes) and the backup and
schema-drift procedures are in [`docs/DATABASE.md`](docs/DATABASE.md).

## Rules a new maintainer must not break

- **Eligibility window** is semesters `max(entry,1)..current`; a backlog never disappears by moving
  up a year. Semester parity (current even, entry odd) applies to students only — subjects use 1–8.
- **Year binding fails closed**: a backlog semester resolves to the year the student first studied
  it (`student_semester_terms`); no row means "contact the department office", never a guess.
- **Academic year is one start-year integer** (`2025`) in the database and API; `2025-26` is display
  only.
- **Registrations are immutable**; verification is one-way and `REJECTED` is terminal.
- **Authorization is server-side.** Hiding a button in React is not access control; every admin
  endpoint scopes by role, department and (for proctors) assigned students.
- **Schema changes only via a new Flyway migration.**

The reasoning behind these is in [`docs/adr/`](docs/adr) — start with `backlog-progression.md`.

## Left to do — for the next maintainer

The app works end to end, but it has only been run on free-tier hosting with test data. Before
real students use it at scale, whoever takes it up should look at the following. "If needed" is
deliberate: judge each item against your own requirements rather than changing things for their
own sake.

### Security

- **Security review.** Review the authentication and authorization handling and improve it where needed
  (for example, login has no rate limiting by design — see `docs/adr/student-authentication.md`).
- **Password handling.** Rework how passwords are handled, in both the logic and the code:
  - New and reset staff accounts get a default password derived from the username
    (`CreateUserRequest`, the user-reset endpoint), which is predictable. Replace it with, for
    example, a random one-time password or an emailed set-password link, and force a change on
    first login.
  - Review the password-change flow (`POST /api/auth/change-password`): strength rules (currently
    only a minimum length), and how it interacts with sign-out and session revocation.
  - Students sign in with USN + date of birth, which is guessable and has no throttling (see
    `docs/adr/student-authentication.md`). Decide whether students need a real password or another
    second factor, and add a matching change/reset flow.
- **Student personal data.** The database holds students' names, dates of birth, phone numbers
  and email addresses, and the date of birth doubles as the login credential. Whoever hosts this
  for real should check the college's data-protection requirements: where the data is stored, who
  can access the database and backups, and how long records are kept.

### Scale and hosting

- **Caching, queueing and waiting.** Add caching, request queueing or a waiting room where load
  requires it — for example, around PDF generation or the rush when a registration window opens.
- **Hosting architecture for concurrent traffic.** Explore hosting architectures and domain
  handling that can serve a large number of students at the same time (a whole college
  registering in the same few days), and decide properly on the hardware and software
  requirements: server size and count, database tier and connection limits, load balancing,
  and so on.
- **Move off free tiers.** Explore the different tools and providers for this job. No free tier
  offers the capacity this needs — the current Render + Neon free setup sleeps when idle, has
  limited compute and a monthly quota. For this to work in practice for a large number of users,
  host it on a proper paid service or on the college's own servers and domain.

### Code health

- **Refactoring.** Refactor the codebase only where it is actually needed; the architecture
  decision records in `docs/adr/` explain why much of the current structure is the way it is.
- **Dependencies.** Some backend and frontend dependencies may be outdated by the time you read
  this. Update them as required (`./mvnw versions:display-dependency-updates`, `npm outdated`),
  checking release notes for breaking changes, and run the full backend and Cypress suites after
  each upgrade. Keep `npm audit --omit=dev` clean.
- **The 9 known frontend lint errors — optional, low value.** `npm run lint` reports 9 errors,
  all `react-hooks/set-state-in-effect`. They are known and harmless: each one is intentional,
  carries a `// NOTE:` comment explaining it, and the app builds and works with them (`npm run
  build` does not run ESLint). CI checks that the count stays at exactly 9 (`npm run lint:count`),
  so a *new* error still fails the build. You can clear them if you want, but be warned: it means
  reworking how those components load and reset their data, which is a lot of change for
  essentially no benefit to users. Not recommended unless you are rewriting those components
  anyway. If you do change the count, update `lint:count` in `frontend/package.json` and the CI
  lint step to match.
- **Primary keys.** Change the primary key columns of the database tables where needed (for
  example `students.roll_no` and `registrations.reg_id` are text keys), and update the
  corresponding entities, repositories and code. Do it through a new Flyway migration, never by
  editing an applied one.
- **UI redesign — free to change.** The current visual redesign was done late in the project and
  only partially: some pages may look unfinished, and some cases have no designed UI and fall
  back to a plain browser message instead (this can be hit in real use on the admin side). Treat
  the UI as a starting point and change or redo it freely.

## License

Released under the [MIT License](LICENSE).
