// An exam cycle's month is stored canonically as "YYYY-MM" ("2026-06"); "June 2026" is pure
// presentation, built here. Same split as academicYear.js, and for the same reason: the stored
// value is what the printed form reads, so it must have exactly one spelling. Free text gave
// three ("June 2026", "2026-06", "9/20/2026") and accepted "Not a valid month/year".
//
// Own module, not a helper inside a .jsx: a non-component export there trips
// react-refresh/only-export-components and takes lint off its known 9 errors.

const MONTH_NAMES = [
  "January", "February", "March", "April", "May", "June",
  "July", "August", "September", "October", "November", "December",
];

/** The picker's options. Values are the stored two-digit month, so no caller pads by hand. */
export const EXAM_MONTHS = MONTH_NAMES.map((label, i) => ({
  value: String(i + 1).padStart(2, "0"),
  label,
}));

const CANONICAL = /^(\d{4})-(0[1-9]|1[0-2])$/;

/**
 * "2026-06" -> "June 2026". Anything else is returned trimmed, UNCHANGED: cycles created before
 * the format existed hold free text, and there is no rule that recovers a month from "Testing".
 * Showing it verbatim keeps it findable and correctable; guessing would invent an exam date.
 * "" for empty, which callers render as a placeholder.
 */
export function formatExamMonth(value) {
  const raw = value == null ? "" : String(value).trim();
  const match = raw.match(CANONICAL);
  if (!match) return raw;
  return `${MONTH_NAMES[Number(match[2]) - 1]} ${match[1]}`;
}

/** true only for the stored form the server accepts — the UI's own copy of its @Pattern. */
export function isCanonicalExamMonth(value) {
  return CANONICAL.test(String(value ?? "").trim());
}

/** ("2026", "06") -> "2026-06"; "" while either half is unset, so a half-filled picker submits
 *  nothing rather than a plausible wrong month. */
export function toExamMonthValue(year, month) {
  const y = String(year ?? "").trim();
  const m = String(month ?? "").trim();
  if (!y || !m) return "";
  return `${y}-${m}`;
}
