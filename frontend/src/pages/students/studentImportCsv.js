// CSV shape for the student importer: column order and the header line. Parsing lives in
// lib/csv.js; this module only maps cells to request fields.
//
// Its own module rather than a helper inside ImportStudentsTab.jsx for two reasons: the
// react-refresh/only-export-components rule bars a non-component export from a .jsx, and a
// POSITIONAL destructure of six columns is exactly the thing worth asserting directly — the only
// other coverage is one DOM round-trip, which cannot tell a column-order slip from a correct parse.
// Mirrors pages/manageSubjects/subjectImportCsv.js.

import { dropHeaderRow, parseCsv } from "../../lib/csv";

export const STUDENT_CSV_HEADER = "USN,name,dateOfBirth,phone,currentSemester,entrySemester";

// Sample rows must satisfy the semester parity rule, or the downloaded template errors on import.
export const STUDENT_CSV_TEMPLATE =
  STUDENT_CSV_HEADER +
  "\n1MS24CS001,Asha Rao,2006-04-12,9999999999,2,1\n1MS24CS002,Migrant Kid,2005-09-01,,4,3";

const HEADER_FIRST_CELLS = ["usn", "rollno"];

/**
 * Parse the pasted CSV into student import rows. Blank semester cells stay null so the batch
 * defaults apply server-side; a blank phone stays null rather than becoming "".
 *
 * @throws {Error} from parseCsv on malformed quoting
 */
export function parseStudentCsv(text) {
  return dropHeaderRow(parseCsv(text), HEADER_FIRST_CELLS).map((cells) => {
    const [rollNo, name, dateOfBirth, phone, currentSemester, entrySemester] = cells;
    return {
      rollNo: rollNo || "",
      name: name || "",
      phone: phone ? phone.replace(/\D/g, "").slice(0, 10) : null,
      dateOfBirth: dateOfBirth || null,
      currentSemester: currentSemester ? Number(currentSemester) : null,
      entrySemester: entrySemester ? Number(entrySemester) : null,
    };
  });
}
