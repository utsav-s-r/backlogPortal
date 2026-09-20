-- The batch list printed above the student's details on every registration form ("B.E. I to VII
-- Semester (2021 Batch Students)" × 8) was a hardcoded String[][] in PdfService, so it went stale
-- every academic year and could only be corrected by a deploy. It is a per-cycle notification from
-- the exam section, so it belongs to the exam cycle.
--
-- Two columns, not one: the renderer supplies the "(", the " Batch Students)" and the bold/regular
-- split that falls INSIDE the parenthesis. Storing the whole line would mean finding the "(" again
-- to know where bold ends — recovering by string-sniffing structure that was thrown away on input.
-- `batch` is text, not a year: a real row reads "2022 & 2023".
--
-- No backfill: every existing cycle is test data (owner, 2026-09-20). Existing cycles therefore
-- print no batch block, and one that already has registrations is frozen and cannot be given
-- lines — accepted for the same reason.
--
-- Mapped as an @ElementCollection on ExamCycle: the lines have no identity or lifetime of their
-- own, so there is no id column and no repository. Hibernate owns `position` (@OrderColumn) and
-- replaces the whole list on every edit.
CREATE TABLE exam_cycle_batch_lines (
    exam_cycle_id bigint NOT NULL,
    position integer NOT NULL,
    label character varying(80) NOT NULL,
    batch character varying(40) NOT NULL,
    -- (cycle, position) is the natural key of an ordered list; it is also what stops two rows
    -- claiming the same slot, which @OrderColumn cannot express.
    CONSTRAINT pk_exam_cycle_batch_lines PRIMARY KEY (exam_cycle_id, position),
    CONSTRAINT fk_exam_cycle_batch_lines_cycle FOREIGN KEY (exam_cycle_id)
        REFERENCES exam_cycles(id) ON DELETE CASCADE
);
