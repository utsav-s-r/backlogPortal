// Academic years are stored canonically as a single START year: 2025 means AY 2025-26. The "-26"
// is always start+1, so it is pure presentation — the int stays the single source of truth in the
// DB, API, and every comparison including the year-binding equality check, and becomes a human
// label only here. See docs/adr/backlog-progression.md.

// 2025 -> "2025-26". "" for empty / non-numeric / non-positive input, which callers render as a
// placeholder.
export function formatAcademicYear(startYear) {
  const start = Number(startYear);
  if (!Number.isInteger(start) || start <= 0) return "";
  const endShort = String(start + 1).slice(-2).padStart(2, "0");
  return `${start}-${endShort}`;
}

// Accepts spans and bare start years alike, so older "2025" inputs and CSV pastes keep working:
//   "2025-26" | "2025-2026" | "2025" | 2025  ->  2025
// NaN when no 4-digit start year can be read; callers treat NaN / empty as "not provided".
export function parseAcademicYear(value) {
  if (value == null) return NaN;
  const match = String(value).trim().match(/^(\d{4})/);
  return match ? Number(match[1]) : NaN;
}

// By institutional convention a course code's first two digits are the start year of the academic
// year it is offered in (22CSL44 -> AY 2022-23). The year is authoritative and stamps this locked
// prefix; only the suffix is editable. These helpers compose/split a code around that rule, which
// the server enforces too via CourseCodes.java. See docs/adr/backlog-progression.md.

// Two-digit prefix for an academic-year start: 2022 -> "22"; "" for invalid input.
export function academicYearPrefix(year) {
  const y = Number(year);
  if (!Number.isInteger(y) || y <= 0) return "";
  return String(((y % 100) + 100) % 100).padStart(2, "0");
}

// The editable part of a code, everything after the two-digit year prefix: "22CSL44" -> "CSL44".
// A code with no numeric prefix is returned as-is.
export function courseCodeSuffix(code) {
  return /^\d{2}/.test(code || "") ? String(code).slice(2) : code || "";
}

// Compose a full course code from an academic year and the editable suffix.
export function buildCourseCode(year, suffix) {
  return academicYearPrefix(year) + (suffix || "");
}
