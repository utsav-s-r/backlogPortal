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

// Years to offer in a "which academic year?" picker: a recent window, plus `selected` when it falls
// outside so an existing value is never silently dropped from its own dropdown. Newest first.
// Shared because Add and Import must offer the SAME list — two copies would drift into one tab
// accepting a year the other refuses.
export function recentAcademicYears(selected) {
  const base = Array.from({ length: 6 }, (_, i) => new Date().getFullYear() - i + 1);
  // `selected ?` before Number, not Number alone: Number("") is 0 and Number.isInteger(0) is true,
  // so an unset picker appended a year 0 whose formatAcademicYear label is "" — a blank, selectable
  // option under the placeholder that the server then 400s on.
  const chosen = selected ? Number(selected) : NaN;
  return Array.from(
    new Set([...base, ...(Number.isInteger(chosen) ? [chosen] : [])]),
  ).sort((a, b) => b - a);
}

// A bare start year, or a span whose end is start+1 (2- or 4-digit end; "-", "–" or "/", spaces
// allowed):  "2025" | 2025 | "2025-26" | "2025-2026" | "2025 / 26"  ->  2025
// NaN for anything else — including "2025-24" or "20251". Never guess a start year out of a string
// that isn't one: this int is the year-binding key, so a typo read as a year silently re-points
// every backlog through it. Callers treat NaN / empty as "not provided" or show an error.
const YEAR_OR_SPAN = /^(\d{4})(?:\s*[-–/]\s*(\d{2}|\d{4}))?$/;

export function parseAcademicYear(value) {
  if (value == null) return NaN;
  const match = String(value).trim().match(YEAR_OR_SPAN);
  if (!match) return NaN;
  const start = Number(match[1]);
  const end = match[2];
  if (end !== undefined) {
    const expected = end.length === 2 ? (start + 1) % 100 : start + 1;
    if (Number(end) !== expected) return NaN;
  }
  return start;
}
