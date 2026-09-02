import { useState } from "react";
import CsvImportPanel from "../../components/CsvImportPanel";
import Field from "../../components/ui/Field";
import { parseStudentCsv, STUDENT_CSV_HEADER, STUDENT_CSV_TEMPLATE } from "./studentImportCsv";
import { CURRENT_SEMESTERS, ENTRY_SEMESTERS } from "../../lib/semesters";
import { FIELD_CONTROL } from "../../lib/formClasses";

// Bulk-import students from CSV: per-row semesters fall back to the batch defaults, existing USNs
// are skipped, dryRun previews without writing. CsvImportPanel owns the shell, the run and the
// result table; this owns the two defaults and the payload.
function ImportStudentsTab() {
  const [defaultCurrent, setDefaultCurrent] = useState("2");
  const [defaultEntry, setDefaultEntry] = useState("1");

  return (
    <CsvImportPanel
      title="Import students (CSV)"
      dataCyPrefix="students-import"
      headerLine={STUDENT_CSV_HEADER}
      placeholder="1MS24CS001,Asha Rao,2006-04-12,9999999999,2,1"
      templateText={STUDENT_CSV_TEMPLATE}
      templateFilename="students-template.csv"
      parse={parseStudentCsv}
      endpoint="/admin/students/import"
      buildPayload={(rows, dryRun) => ({
        rows,
        defaultCurrentSemester: defaultCurrent ? Number(defaultCurrent) : null,
        defaultEntrySemester: defaultEntry ? Number(defaultEntry) : null,
        dryRun,
      })}
      verb="Imported"
      idLabel="USN"
      idKey="rollNo"
      description={
        <>
          One row per line: <code>{STUDENT_CSV_HEADER}</code>. Date of birth is{" "}
          <code>yyyy-MM-dd</code>. Phone is optional; email is assigned automatically as{" "}
          <code>usn@msrit.edu</code>. Current semester must be even (2, 4, 6, 8) and entry semester
          odd (1, 3, 5, 7) — entry is where the student joined, so <code>3</code> or above means
          lateral entry. Leave the two semester columns blank to use the batch defaults below.
          Existing USNs are skipped, so it&apos;s safe to re-run. Preview first to check.
        </>
      }
      controls={
        <>
          <Field label="Default current sem">
            <select
              className={`${FIELD_CONTROL} w-32`}
              value={defaultCurrent}
              onChange={(e) => setDefaultCurrent(e.target.value)}
              data-cy="students-import-default-current"
            >
              {CURRENT_SEMESTERS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </Field>
          <Field label="Default entry sem">
            <select
              className={`${FIELD_CONTROL} w-32`}
              value={defaultEntry}
              onChange={(e) => setDefaultEntry(e.target.value)}
              data-cy="students-import-default-entry"
            >
              {ENTRY_SEMESTERS.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </Field>
        </>
      }
    />
  );
}

export default ImportStudentsTab;
