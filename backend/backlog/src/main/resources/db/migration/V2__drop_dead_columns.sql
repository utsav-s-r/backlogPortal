-- Drop two pre-Flyway leftovers that no entity maps. Both survived because they are NULLable and
-- ddl-auto=validate only checks that MAPPED columns exist -- it never reports extra ones.
--
-- students.password_hash  -- predates USN+DOB login (docs/adr/student-authentication.md); students
--                            authenticate on date_of_birth and there is no password column in the
--                            Student entity.
-- subjects.year_of_joining -- superseded by academic_year_offered. The only year_of_joining mapping
--                            in the model is Student's; Subject has none.
--
-- IF EXISTS so this is a no-op on a database freshly built from V1 (which, being an exact snapshot
-- of Neon, still carries both). On Neon, where V1 is recorded as the baseline and never executed,
-- this is the statement that actually removes them.

ALTER TABLE students DROP COLUMN IF EXISTS password_hash;
ALTER TABLE subjects DROP COLUMN IF EXISTS year_of_joining;
