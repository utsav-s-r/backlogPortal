// Shared render for the batch-result DTO (ProgressionRowResult rows + created/skipped/errors
// counts) that student import and proctor claim both return. Keep it shared: as two copies the
// rows guard was written twice and had already drifted to a weaker operator.

import { batchRows } from "./batchResult";

// Union of the two endpoints' statuses; the WOULD_* pair is the dry-run preview.
const STATUS_STYLES = {
  CREATED: "text-primary-ink",
  WOULD_CREATE: "text-primary-ink",
  SKIPPED_EXISTS: "text-ink-muted",
  WOULD_SKIP: "text-ink-muted",
  ERROR: "text-red-600",
};

/**
 * @param verb  past-tense word for a real run ("Imported"); a dry run says Preview
 */
function BatchResultTable({ result, verb, dataCy }) {
  if (!result) return null;

  const rows = batchRows(result);

  return (
    <div className="mt-4" data-cy={dataCy}>
      <p className="mb-2 text-sm font-medium text-ink">
        {result.dryRun ? "Preview" : verb} — {result.created} created, {result.skipped} skipped,{" "}
        {result.errors} error(s)
      </p>
      <div className="max-h-72 overflow-auto rounded-xl border border-stroke">
        <table className="w-full text-left text-sm">
          <thead className="sticky top-0 bg-surface-muted text-xs uppercase tracking-[0.08em] text-ink-muted">
            <tr>
              <th className="px-3 py-2">USN</th>
              <th className="px-3 py-2">Sem</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Detail</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r.rollNo}-${r.semester ?? ""}-${i}`} className="border-t border-stroke">
                <td className="px-3 py-2 font-mono text-xs">{r.rollNo}</td>
                <td className="px-3 py-2">{r.semester ?? "—"}</td>
                <td className={`px-3 py-2 font-semibold ${STATUS_STYLES[r.status] || ""}`}>{r.status}</td>
                <td className="px-3 py-2 text-ink-muted">{r.message || ""}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </div>
  );
}

export default BatchResultTable;
