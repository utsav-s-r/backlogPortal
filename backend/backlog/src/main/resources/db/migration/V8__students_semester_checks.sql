-- The semester rules were enforced in application code only (StudentManagementService
-- .validateSemesters — create, edit and CSV import all route through it). That is why no invalid
-- row exists and why none can be created today. What was missing is the guarantee: the rules held
-- because three call sites remembered to ask, not because the database refused.
--
-- Two open issues turned on that distinction. Both described rows the application cannot produce:
-- a current_semester of 0 promoting to 2 and being recorded PROMOTED, and a semester-8 student
-- with entry 9 counted "at max" by the preview but recorded SKIPPED_INVALID_SEMESTER by the
-- audit. Guarding those in the progression SQL would add conditions no test can exercise, in the
-- one place the repository's own comment says two copies must never disagree. Stating the rule
-- once, here, makes both cases impossible instead of merely unlikely — and makes a future write
-- path that forgets the service fail loudly rather than quietly storing a student nobody can
-- register.
--
-- Parity is a year being a semester PAIR: a student sits in an even semester and joins at an odd
-- one, and progression's +2 preserves it. Mirrors chk_progression_batches_filter_semester, which
-- already constrains the same vocabulary one table over.
--
-- NOT VALID, deliberately. Existing rows are NOT checked: the live data cannot be inspected from
-- here, and a legacy row would otherwise fail the migration and the whole deploy with it. Both
-- constraints are still enforced on every INSERT and on every UPDATE from now on, including an
-- edit that tries to leave a legacy row invalid. That matches the standing decision that odd
-- legacy semesters are REPORTED, never auto-corrected: the Manage filter spans 1..8 so they stay
-- findable, the edit form marks the stored value invalid, and bulk progression skips them.
--
-- To promote these to fully validated once the data is known clean:
--   select count(*) from students
--    where current_semester not in (2,4,6,8)
--       or entry_semester not in (1,3,5,7)
--       or entry_semester > current_semester;   -- expect 0
--   alter table students validate constraint chk_students_semester_parity;
--   alter table students validate constraint chk_students_entry_not_after_current;
ALTER TABLE students
    ADD CONSTRAINT chk_students_semester_parity
    CHECK (current_semester IN (2, 4, 6, 8) AND entry_semester IN (1, 3, 5, 7))
    NOT VALID;

ALTER TABLE students
    ADD CONSTRAINT chk_students_entry_not_after_current
    CHECK (entry_semester <= current_semester)
    NOT VALID;
