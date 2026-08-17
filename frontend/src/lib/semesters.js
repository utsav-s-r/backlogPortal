/**
 * Frontend mirror of Semesters.java. The server is authoritative — these lists only stop an admin
 * picking a value the API would reject.
 *
 * The distinction is the whole point and is easy to get wrong:
 *
 *   ALL_SEMESTERS       1..8 — SUBJECT semesters, timeline rows, clone targets, registration
 *                       filters. A sem-2 student's backlogs are in sem 1, so odd semesters must
 *                       stay selectable everywhere a SUBJECT is chosen.
 *   CURRENT_SEMESTERS   2,4,6,8 — where a STUDENT sits. An academic year is a semester pair, so
 *                       progression moves +2 and a student is only ever in an even semester.
 *   ENTRY_SEMESTERS     1,3,5,7 — where a STUDENT joined. Entry is always at the start of a year.
 *
 * Narrowing a subject picker to even semesters would silently hide half of every student's
 * backlogs; widening a student picker to odd would let the API 400 instead.
 *
 * Own module, not a helper inside a .jsx: a non-component export in a .jsx trips
 * react-refresh/only-export-components and takes lint off its known 9 errors.
 */

export const ALL_SEMESTERS = [1, 2, 3, 4, 5, 6, 7, 8];
export const CURRENT_SEMESTERS = [2, 4, 6, 8];
export const ENTRY_SEMESTERS = [1, 3, 5, 7];

/**
 * Add a stored-but-now-invalid semester to an option list, in order.
 *
 * A `<select>` whose `value` matches no `<option>` renders blank while React state still holds the
 * old value — so the form shows one thing and submits another. Legacy rows (odd currentSemester,
 * even entrySemester) hit exactly that. Surfacing the stored value keeps display and payload in
 * step and lets the admin deliberately pick a valid one; the server still rejects a save that
 * leaves it unchanged, which is the point — it is reported, never silently rewritten.
 */
export function withLegacyValue(options, stored) {
  const value = Number(stored) || 0;
  if (!value || options.includes(value)) return options;
  return [...options, value].sort((a, b) => a - b);
}

/** Entry semesters valid for a given current semester — entry can never be after current. */
export function entrySemestersUpTo(currentSemester) {
  const current = Number(currentSemester) || 0;
  const options = ENTRY_SEMESTERS.filter((s) => s <= current);
  // a current semester below 1 (or not yet chosen) still needs a usable list
  return options.length > 0 ? options : [ENTRY_SEMESTERS[0]];
}

/**
 * Snap an entry semester DOWN when the current semester drops below it. Only that case — the
 * entry semester is never otherwise rewritten.
 *
 * A legacy even entry (the rows the parity rule newly makes invalid) is returned UNCHANGED, so the
 * server reports it and an admin fixes it deliberately. Guessing a replacement here raises the
 * eligibility floor and silently hides the student's older backlogs, which is precisely the
 * "reported, never guessed at" rule in docs/adr/backlog-progression.md.
 */
export function clampEntrySemester(entrySemester, currentSemester) {
  const entry = Number(entrySemester) || 0;
  if (entry <= (Number(currentSemester) || 0)) return entry;
  const options = entrySemestersUpTo(currentSemester);
  return options[options.length - 1]; // largest odd entry still <= current
}
