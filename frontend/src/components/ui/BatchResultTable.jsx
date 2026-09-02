// Shared render for the batch-result DTO (per-row results + created/skipped/errors counts) that
// student import, proctor claim and subject import all return. Keep it shared: as two copies the
// rows guard was written twice and had already drifted to a weaker operator.
//
// The only thing that varies between callers is what identifies a row — a USN for students, a
// course code for subjects — so that is a prop rather than a second component.

import { batchRows } from "../../lib/batchResult";
import { batchStatusClass } from "../../lib/batchStatus";

/**
 * No defaults on idLabel/idKey on purpose: the one mistake a new caller can make here is forgetting
 * them, and a "USN" default renders a confident wrong heading over a column of blanks while React
 * still builds unique keys from the row index, so nothing complains. Required, it fails visibly.
 *
 * @param verb     past-tense word for a real run ("Imported"); a dry run says Preview
 * @param idLabel  column heading for the identifying field ("USN", "Course code")
 * @param idKey    the field on each row holding it ("rollNo", "courseCode")
 */
function BatchResultTable({ result, verb, dataCy, idLabel, idKey }) {
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
              <th className="px-3 py-2">{idLabel}</th>
              <th className="px-3 py-2">Sem</th>
              <th className="px-3 py-2">Status</th>
              <th className="px-3 py-2">Detail</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((r, i) => (
              <tr key={`${r[idKey]}-${r.semester ?? ""}-${i}`} className="border-t border-stroke">
                <td className="px-3 py-2 font-mono text-xs">{r[idKey]}</td>
                <td className="px-3 py-2">{r.semester ?? "—"}</td>
                <td className={`px-3 py-2 font-semibold ${batchStatusClass(r.status)}`}>{r.status}</td>
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
