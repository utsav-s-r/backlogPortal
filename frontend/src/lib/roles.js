/**
 * Frontend mirror of UserRole.java. The server is authoritative — these lists only stop the UI
 * offering a page or control the API would refuse. NONE of them is a control: every check here is
 * re-made server-side per endpoint via @PreAuthorize plus the caller's own scope resolution.
 *
 * The subsets are the whole point and are easy to get wrong:
 *
 *   STAFF_ROLES              all five — mirrors SecurityConfig's /api/admin/** hasAnyRole, i.e.
 *                            "may reach the admin side at all". The route-guard set.
 *   SUBJECT_ROLES            the four that reach the subject catalog; PROCTOR is excluded by
 *                            SubjectController's own @PreAuthorize.
 *   UNRESTRICTED             ADMIN + PRINCIPAL — act college-wide, pinned to no department. The
 *                            frontend face of CallerScope's "null means genuinely unrestricted".
 *   DEPT_PINNED              HOD + DEPT_OFFICE + PROCTOR — scoped to their own department.
 *   DEPT_PINNED_NO_PROCTOR   HOD + DEPT_OFFICE.
 *
 * The TWO dept-pinned sets are deliberate and MUST NOT be merged. They mirror the backend's own
 * split, where each controller keeps its own DEPT_ROLES for the same reason: a single shared set
 * would silently widen or narrow one of them. Five controllers (Auth, Admin, Progression,
 * StudentManagement, UserManagement) use {HOD, DEPT_OFFICE, PROCTOR}; three (Subject, Registration,
 * SubjectClone) use {HOD, DEPT_OFFICE}.
 *
 * One lookup idiom on purpose: every export is a frozen array and every call site uses .includes().
 * At five elements a Set buys nothing, and a second idiom for the same question is pure reading cost.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */

/** The UserRole enum, for single-role comparisons (`adminRole === ROLE.PROCTOR`). */
export const ROLE = Object.freeze({
  ADMIN: "ADMIN",
  PRINCIPAL: "PRINCIPAL",
  HOD: "HOD",
  DEPT_OFFICE: "DEPT_OFFICE",
  PROCTOR: "PROCTOR",
});

/** Every staff role. "May reach the admin side", not "may do anything there". */
export const STAFF_ROLES = Object.freeze([
  ROLE.ADMIN,
  ROLE.PRINCIPAL,
  ROLE.HOD,
  ROLE.DEPT_OFFICE,
  ROLE.PROCTOR,
]);

/** Reaches the subject catalog. PROCTOR excluded — SubjectController refuses it. */
export const SUBJECT_ROLES = Object.freeze([
  ROLE.ADMIN,
  ROLE.PRINCIPAL,
  ROLE.HOD,
  ROLE.DEPT_OFFICE,
]);

/** College-wide, pinned to no department. */
export const UNRESTRICTED = Object.freeze([ROLE.ADMIN, ROLE.PRINCIPAL]);

/** Scoped to their own department. The majority spelling — see the note above before reusing. */
export const DEPT_PINNED = Object.freeze([ROLE.HOD, ROLE.DEPT_OFFICE, ROLE.PROCTOR]);

/** Dept-scoped where PROCTOR is excluded earlier. NOT interchangeable with DEPT_PINNED. */
export const DEPT_PINNED_NO_PROCTOR = Object.freeze([ROLE.HOD, ROLE.DEPT_OFFICE]);

/** Manages staff accounts. PROCTOR and DEPT_OFFICE manage nobody. */
export const USER_MANAGEMENT_ROLES = Object.freeze([ROLE.ADMIN, ROLE.PRINCIPAL, ROLE.HOD]);

/** ADMIN alone. PRINCIPAL is excluded on purpose wherever this is used — exam cycles are the
 *  college-wide registration switch (ExamCycleController) and bulk progression is an
 *  institution-wide write (owner decision). A ONE-element list, not a bare comparison,
 *  so it can be passed to useRoleGuard like every other gate. */
export const ADMIN_ONLY = Object.freeze([ROLE.ADMIN]);

/**
 * ⚠️ LINT CONSTRAINT — a hard rule here, not a style preference.
 *
 * A component-scope const that feeds a hook dependency array must NOT be computed with
 * `SOME_ARRAY.includes(role)` where SOME_ARRAY is a named binding. `eslint-plugin-react-hooks` runs
 * the React Compiler's static analysis; it cannot prove the array is unmodified, so the value goes
 * opaque and `preserve-manual-memoization` reports "Existing memoization could not be preserved" —
 * an ESLint ERROR here, breaking the repo's 9-errors-0-warnings invariant.
 *
 * The cost is the LINT BUDGET, not runtime. The compiler is NOT enabled in the build — vite.config
 * calls `react()` with no options and `babel-plugin-react-compiler` is not installed — so nothing
 * is auto-memoized either way. Do not reason about this as a lost optimization.
 *
 * The rule is exact:
 *   ✗ `const x = STAFF_ROLES.includes(r)`        imported binding      — bails
 *   ✗ `const x = LOCAL_ARRAY.includes(r)`        module-local binding  — bails too, so this is
 *                                                NOT about imports
 *   ✗ `const x = isStaff(r)`                     predicate function    — bails too, so wrapping
 *                                                it does not help
 *   ✓ `const x = [ROLE.A, ROLE.B].includes(r)`   INLINE array literal  — fine
 *   ✓ `const x = r === ROLE.A || r === ROLE.B`   direct comparison     — fine
 *   ✓ `{STAFF_ROLES.includes(r) && <Link/>}`     inline in JSX, not stored in a const — fine
 *
 * So: use the named sets freely in JSX and in any value no hook depends on. Where a hook DOES
 * depend on the result, spell it as an inline literal of ROLE members — that keeps the vocabulary
 * (no bare strings) without the bailout. AdminPage's `isAdmin` and its `filter` seed are the two
 * live examples.
 */
