/**
 * Batch result-row status colours. One map because SubjectCloneResult,
 * SubjectClonePreviewResponse and ProgressionRowResult (import + proctor claim) share one
 * vocabulary: CREATED | SKIPPED_EXISTS | ERROR + the WOULD_* dry-run pair.
 *
 * Two neighbouring vocabularies stay separate: registration verification (VERIFIED/REJECTED,
 * pages/admin/outcomeBadge.js) and bulk progression (SKIPPED_AT_MAX, SKIPPED_INVALID_SEMESTER,
 * BulkProgressionTab's OUTCOME_LABEL). Don't fold either in.
 *
 * Own .js module: a non-component export in a .jsx trips react-refresh/only-export-components and
 * takes lint off its known 9 errors.
 */
const STATUS_STYLES = {
  CREATED: "text-primary-ink",
  WOULD_CREATE: "text-primary-ink",
  SKIPPED_EXISTS: "text-ink-muted",
  WOULD_SKIP: "text-ink-muted",
  ERROR: "text-red-600",
};

/** Falls back to inherited colour, so an unrecognised status still renders its own text. */
export function batchStatusClass(status) {
  return STATUS_STYLES[status] || "";
}
