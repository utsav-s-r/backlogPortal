-- A session token carried two claims — who (username / roll number) and what role — and nothing
-- said WHEN it was issued relative to the account. Two consequences, both fail-open:
--
--   * Reusing a username revived a dead session with the NEW account's scope. Rename CSE `hod` to
--     `hod_cse`, create a Civil HOD called `hod` within the hour, and the old CSE token resolves
--     again: same subject, same role, a different department. Delete-and-recreate a proctor the
--     same way and the old token inherits the new proctor's students.
--   * Changing a password, resetting one, or resetting a student's date of birth left every
--     existing session working for up to an hour — the credential changed, the tokens did not.
--
-- One timestamp per account fixes both: a token whose `iat` is STRICTLY older than this is
-- refused (AccountExistenceFilter). Strictly, because the column is also stamped at account
-- CREATION — that is what stops a recreated username inheriting anything — and a login in the
-- same second as the creation must not invalidate itself.
--
-- DEFAULT now() backfills existing rows, so every session live at deploy is refused. Sessions are
-- a fixed 1h and a deploy restarts the app regardless; the alternative (backdating to epoch)
-- would leave exactly the tokens this exists to kill.
--
-- Not nullable: a null would have to mean "never revoke", which is the fail-open being removed.
-- The default stays on the column so a future INSERT that forgets the field is safe by accident
-- rather than broken by it.
ALTER TABLE users
    ADD COLUMN session_valid_from timestamp(6) with time zone NOT NULL DEFAULT now();

ALTER TABLE students
    ADD COLUMN session_valid_from timestamp(6) with time zone NOT NULL DEFAULT now();

-- Folded in while V7 was still unapplied: proctor_students.assigned_at was the LAST
-- `timestamp without time zone` in the schema. V6 fixed registrations.registered_at and its
-- comment claimed it was the only one — it was not, and this is the correction.
--
-- Same defect, one step from surfacing: LocalDateTime.now() writes the SERVER's wall clock (UTC
-- on Render, IST from a laptop) and ProctorAssignmentController serialises it offset-less, so a
-- browser would read it 5h30 early. Harmless only because nothing renders it yet — the API sends
-- the field and no page shows it. Fixing it now costs one line; fixing it after someone adds an
-- "assigned on" column costs a bug report.
--
-- Existing values read as UTC. Rows written by the column DEFAULT now() already are (Postgres,
-- on Neon, runs UTC); rows written by Java carry whatever zone that JVM had. Test data either way.
ALTER TABLE proctor_students
    ALTER COLUMN assigned_at TYPE timestamp(6) with time zone
    USING assigned_at AT TIME ZONE 'UTC';
