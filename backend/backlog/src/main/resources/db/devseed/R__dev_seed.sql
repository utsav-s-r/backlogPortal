-- ============================================================================
-- DEV-ONLY demo seed (departments + a few subjects), for clicking around a fresh
-- local database. This is a Flyway REPEATABLE migration in a SEPARATE location
-- (db/devseed, NOT under db/migration) so Flyway's default recursive scan of
-- classpath:db/migration never picks it up. It runs ONLY when the `dev` profile
-- adds classpath:db/devseed to spring.flyway.locations (see application-dev.properties).
-- Production and the default/test profiles never see it, so real databases are
-- never seeded with demo rows.
--
-- Every insert is guarded (idempotent) so re-runs are safe.
-- ============================================================================

INSERT INTO departments (dept_name, dept_code, contact_email)
SELECT 'CSE', 'CS', 'cse@example.edu'
WHERE NOT EXISTS (SELECT 1 FROM departments WHERE dept_name = 'CSE');

INSERT INTO departments (dept_name, dept_code, contact_email)
SELECT 'ISE', 'IS', 'ise@example.edu'
WHERE NOT EXISTS (SELECT 1 FROM departments WHERE dept_name = 'ISE');

INSERT INTO subjects (subject_name, course_code, credits, semester, academic_year_offered, subject_type, dept_id)
SELECT 'Mathematics-I', '22MAT11', 4, 1, 2022, 'REGULAR', d.id
FROM departments d
WHERE d.dept_name = 'CSE'
  AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.subject_name = 'Mathematics-I');

INSERT INTO subjects (subject_name, course_code, credits, semester, academic_year_offered, subject_type, dept_id)
SELECT 'Programming Fundamentals', '22CSE12', 3, 1, 2022, 'REGULAR', d.id
FROM departments d
WHERE d.dept_name = 'CSE'
  AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.subject_name = 'Programming Fundamentals');

INSERT INTO subjects (subject_name, course_code, credits, semester, academic_year_offered, subject_type, dept_id)
SELECT 'Data Structures', '22CS32', 4, 3, 2022, 'REGULAR', d.id
FROM departments d
WHERE d.dept_name = 'CSE'
  AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.subject_name = 'Data Structures');

INSERT INTO subjects (subject_name, course_code, credits, semester, academic_year_offered, subject_type, dept_id)
SELECT 'Database Systems', '23IS41', 4, 4, 2023, 'REGULAR', d.id
FROM departments d
WHERE d.dept_name = 'ISE'
  AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.subject_name = 'Database Systems');

INSERT INTO subjects (subject_name, course_code, credits, semester, academic_year_offered, subject_type, dept_id)
SELECT 'Operating Systems', '24CS53', 3, 5, 2024, 'REGULAR', d.id
FROM departments d
WHERE d.dept_name = 'CSE'
  AND NOT EXISTS (SELECT 1 FROM subjects s WHERE s.subject_name = 'Operating Systems');
