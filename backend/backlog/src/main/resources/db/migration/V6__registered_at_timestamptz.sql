-- registered_at was the ONLY `timestamp without time zone` in the schema: registration_events
-- ."timestamp", admin_audit_events."timestamp" and exam_cycles.created_at are all
-- `timestamp with time zone` mapped to Instant. That exception is the bug, not a formatting one —
-- LocalDateTime.now() writes the SERVER's wall clock, which is UTC on Render and IST from a
-- laptop, so the column did not identify a point in time and nothing downstream could recover one.
--
-- Symptoms it produced: the API emitted an offset-less "2026-09-20T05:12:33", which JavaScript
-- parses as LOCAL time, so every registration read 5h30 early against the history dialog (already
-- an Instant) for the same record; and the printed form, formatting the value directly, showed the
-- PREVIOUS day for anything registered between 00:00 and 05:30 IST.
--
-- USING ... AT TIME ZONE 'UTC' reads each stored wall clock as UTC. That is right for every row
-- written on Render, which runs UTC; a row written by a locally-run app against Neon shifts by the
-- laptop's offset. Accepted: everything on Neon is test data (owner, 2026-09-20).
--
-- The two indexes on this column are rebuilt by the rewrite; comparison stays absolute, so
-- ordering is unaffected. Nullability is left exactly as it was — every write path sets the value,
-- but tightening it is a separate decision from fixing the type.
ALTER TABLE registrations
    ALTER COLUMN registered_at TYPE timestamp(6) with time zone
    USING registered_at AT TIME ZONE 'UTC';
