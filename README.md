# Backlog Registration Portal

A full-stack web app that digitises student registration for backlog (supplementary) examinations at
an engineering college. Students sign in, pick the subjects they're eligible to re-attempt, and
download a pre-filled PDF form; departmental staff verify the signed hard copy from a role-scoped
admin dashboard.

Replaces a paper process where students hand-filled forms and staff re-keyed hundreds of them into a
spreadsheet.

## How it works

**Student** — signs in with USN and date of birth, sees only the subjects they're eligible for,
registers while an exam cycle is open, downloads the pre-filled PDF, and submits the signed copy to
the department office.

**Staff** — browse and filter registrations, verify or reject them against the hard copy, and
maintain the data behind the process: subject catalog, student accounts and semester progression,
departments, exam cycles and staff users.

## Features

- Eligibility computed server-side; subjects are bound to the academic year the student first
  studied that semester.
- Registrations are immutable history — `SUBMITTED → VERIFIED | REJECTED`, with an audit trail.
- Server-side PDF generation for the student form and bulk staff exports.
- CSV import and year-to-year cloning for subjects; CSV import and audited bulk progression for
  student accounts.
- Five staff roles with department and per-student scoping, enforced server-side on every endpoint.
- Light/dark theming, responsive to mobile, one-hour non-renewable sessions.

## Roles

| Role | Scope |
| :--- | :--- |
| `ADMIN` | College-wide, including bulk progression and staff renames |
| `PRINCIPAL` | College-wide |
| `HOD` | Own department, plus creating `DEPT_OFFICE`/`PROCTOR` users |
| `DEPT_OFFICE` | Own department |
| `PROCTOR` | Own department, and only an explicitly assigned set of students |

## Stack

| Area | Technology |
| :--- | :--- |
| Backend | Java 21, Spring Boot 3.5, Spring Data JPA, Spring Security, Flyway, iText |
| Frontend | React 19, Vite 8, React Router 7, Tailwind CSS 4, axios |
| Database | PostgreSQL 18 |
| Testing | JUnit 5 + Mockito (backend), Cypress (end-to-end) |

## Setup

Needs **JDK 21**, **Node ≥ 22.12**, and **PostgreSQL 18**. Maven comes from the wrapper.

Two files are gitignored; create them from their tracked templates:

```bash
cd backend/backlog
cp src/main/resources/application.properties.example src/main/resources/application.properties
cp .env.example .env
```

Then set in `.env`:

- `DB_URL` (JDBC spelling: `jdbc:postgresql://host:5432/db`), `DB_USER`, `DB_PASSWORD`
- `JWT_SECRET` — 32 bytes minimum (`openssl rand -base64 48`)
- `ADMIN_PASSWORD_ADMIN` — seeds the bootstrap admin on a fresh database; every other account is
  created from Manage Users once signed in

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
```

Architecture decision records are in [`docs/adr/`](docs/adr).
