import { useState } from "react";
import { Download, LoaderCircle, Search, UploadCloud } from "lucide-react";
import PrimaryCta from "./ui/PrimaryCta";
import BatchResultTable from "./ui/BatchResultTable";
import api from "../lib/api";
import { saveBlob } from "../lib/download";
import { FIELD_INPUT } from "../lib/formClasses";
import { BTN_QUIET } from "../lib/buttonClasses";

// The shell every CSV importer shares — heading, prose, a controls row ending in Template, the
// textarea, the error line, Preview/Import, the result table — plus the run itself, since the
// parse -> post -> report failure handling is the part worth having one copy of.
//
// In components/, NOT components/ui/: it performs the request, so it is a smart shell like
// AdminTabPage rather than a primitive like BatchResultTable. Callers stay presentational — their
// own controls, parser and payload.

/**
 * @param controls         caller's own inputs; rendered before the Template button in the same row
 * @param headerLine       CSV header, quoted back in the "paste at least one row" message
 * @param parse            (csvText) => rows; may THROW on malformed quoting, message shown as-is
 * @param validate         optional () => string | null; a string short-circuits the run as an error.
 *                         Runs BEFORE parse, so "choose a year" beats "paste at least one row" —
 *                         import-subjects.cy.js pins that order with an empty textarea.
 * @param buildPayload     (rows, dryRun) => request body
 * @param verb             past-tense word for a real run, e.g. "Imported"
 * @param idLabel/idKey    identifying column of the result table ("USN"/"rollNo")
 * @param dataCyPrefix     "students-import" -> -template, -csv, -error, -preview, -apply, -result
 */
function CsvImportPanel({
  title,
  description,
  controls,
  headerLine,
  placeholder,
  templateText,
  templateFilename,
  parse,
  validate,
  endpoint,
  buildPayload,
  verb,
  idLabel,
  idKey,
  dataCyPrefix,
}) {
  const [csv, setCsv] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const [result, setResult] = useState(null);

  // Plain function, not useCallback: it closes over parse/validate/buildPayload, which are new
  // closures every caller render, so memoising would preserve nothing.
  const run = async (dryRun) => {
    setError("");
    // the card describes ONE run; cleared here, not in the catch, so the early returns below are
    // covered too
    setResult(null);

    const invalid = validate?.();
    if (invalid) {
      setError(invalid);
      return;
    }

    let rows;
    try {
      rows = parse(csv);
    } catch (parseError) {
      // parse throws only on malformed quoting, with a message written for an admin
      setError(parseError.message);
      return;
    }
    if (rows.length === 0) {
      setError("Paste at least one row: " + headerLine);
      return;
    }

    setBusy(true);
    try {
      const res = await api.post(endpoint, buildPayload(rows, dryRun));
      setResult(res.data);
    } catch (err) {
      setError(err.response?.data?.message || "Import failed.");
    } finally {
      setBusy(false);
    }
  };

  // saveBlob, not a hand-rolled anchor: an immediate revokeObjectURL cancels the download outright
  // on iOS Safari, which consumes the blob URL asynchronously.
  const downloadTemplate = () => saveBlob(templateText, templateFilename, "text/csv");

  return (
    <section className="py-5 sm:py-6">
      <h2 className="mb-1 inline-flex items-center gap-2 text-lg font-semibold text-secondary-ink">
        <UploadCloud size={18} /> {title}
      </h2>
      <p className="mb-3 text-sm text-ink-muted">{description}</p>

      <div className="mb-3 flex flex-wrap items-end gap-3">
        {controls}
        <button
          type="button"
          onClick={downloadTemplate}
          className="inline-flex items-center gap-2 rounded-lg bg-surface-muted px-3 py-2 text-sm font-semibold transition-colors hover:bg-primary-tint"
          data-cy={`${dataCyPrefix}-template`}
        >
          <Download size={15} /> Template
        </button>
      </div>

      <textarea
        className={`${FIELD_INPUT} min-h-32 font-mono`}
        placeholder={placeholder}
        value={csv}
        onChange={(e) => setCsv(e.target.value)}
        data-cy={`${dataCyPrefix}-csv`}
      />

      {error && (
        <p className="mt-3 text-sm text-red-600" role="alert" data-cy={`${dataCyPrefix}-error`}>
          {error}
        </p>
      )}

      <div className="mt-4 flex flex-wrap gap-3">
        <button
          type="button"
          onClick={() => run(true)}
          disabled={busy}
          data-cy={`${dataCyPrefix}-preview`}
          className={BTN_QUIET}
        >
          {busy ? <LoaderCircle size={15} className="animate-spin" /> : <Search size={15} />} Preview
        </button>
        <PrimaryCta
          type="button"
          onClick={() => run(false)}
          disabled={busy}
          className="gap-2 rounded-xl"
          data-cy={`${dataCyPrefix}-apply`}
        >
          <UploadCloud size={15} /> Import
        </PrimaryCta>
      </div>

      <BatchResultTable
        result={result}
        verb={verb}
        dataCy={`${dataCyPrefix}-result`}
        idLabel={idLabel}
        idKey={idKey}
      />
    </section>
  );
}

export default CsvImportPanel;
