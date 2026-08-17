// The batch-result DTO (created/skipped/errors counts + ProgressionRowResult rows) returned by
// student import and proctor claim. Own module, not BatchResultTable.jsx:
// react-refresh/only-export-components bars a non-component export from a component file.

/**
 * The one place the missing-rows decision is made. A failed or empty run can legitimately answer
 * without `results`, so absence is coerced — but Array.isArray, not `?? []`, so a non-array (a
 * broken contract, not an empty run) still fails loudly.
 */
export function batchRows(result) {
  return Array.isArray(result?.results) ? result.results : [];
}
