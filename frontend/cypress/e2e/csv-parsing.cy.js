// The CSV parser and the subject-row mapping, asserted directly rather than through the DOM.
//
// Worth testing this way: the failure mode is SILENT. A line-splitting parser given
// `CSL55,"Design, Analysis of Algorithms",5,3` produces five cells instead of four and shifts every
// later value one column left, so credits land in subjectType and the row imports as a plausible
// wrong subject with no error anywhere. Nothing in the DOM distinguishes that from a correct
// import, so a page-level spec could not catch it. Mutation-tested: replacing parseCsv's body with
// `text.split("\n").map(l => l.split(","))` must fail the quoting cases below.
import { parseCsv } from "../../src/lib/csv";
import { parseSubjectCsv, SUBJECT_CSV_TEMPLATE } from "../../src/pages/manageSubjects/subjectImportCsv";
import { parseStudentCsv, STUDENT_CSV_TEMPLATE } from "../../src/pages/students/studentImportCsv";

describe("parseCsv", () => {
  it("splits plain rows and trims unquoted cells", () => {
    expect(parseCsv("a,b,c\n d , e ,f")).to.deep.equal([
      ["a", "b", "c"],
      ["d", "e", "f"],
    ]);
  });

  it("keeps a comma inside a quoted cell", () => {
    expect(parseCsv('CSL55,"Design, Analysis of Algorithms",5')).to.deep.equal([
      ["CSL55", "Design, Analysis of Algorithms", "5"],
    ]);
  });

  it("preserves whitespace inside quotes but not outside them", () => {
    // quoting a value is an explicit statement about its content, so it is not trimmed
    expect(parseCsv('" padded ", trimmed ')).to.deep.equal([[" padded ", "trimmed"]]);
  });

  it("reads a doubled quote as one literal quote", () => {
    expect(parseCsv('"a ""quoted"" word",b')).to.deep.equal([['a "quoted" word', "b"]]);
  });

  it("keeps a newline inside a quoted cell", () => {
    expect(parseCsv('"line one\nline two",b')).to.deep.equal([["line one\nline two", "b"]]);
  });

  it("treats a quote in the middle of a cell as literal", () => {
    // only a quote at the start of a cell opens a quoted field, so this cannot hijack the file
    expect(parseCsv('6" ruler,b')).to.deep.equal([['6" ruler', "b"]]);
  });

  it("accepts CRLF as well as LF", () => {
    expect(parseCsv("a,b\r\nc,d")).to.deep.equal([
      ["a", "b"],
      ["c", "d"],
    ]);
  });

  it("drops blank lines and a trailing newline", () => {
    expect(parseCsv("a,b\n\n\nc,d\n")).to.deep.equal([
      ["a", "b"],
      ["c", "d"],
    ]);
  });

  it("throws on an unclosed quote instead of swallowing the rest of the file", () => {
    // the lenient reading is one giant cell, which imports as a single absurd row and reads as
    // a one-row file — the exact silent misread this parser exists to prevent
    expect(() => parseCsv('a,"unterminated\nb,c')).to.throw("Unclosed quote");
  });

  it("returns no rows for empty input", () => {
    expect(parseCsv("")).to.deep.equal([]);
    expect(parseCsv(null)).to.deep.equal([]);
  });
});

describe("parseSubjectCsv", () => {
  it("maps cells to request fields and splits dept codes on |", () => {
    expect(parseSubjectCsv("CSL55,Machine Learning,5,3,ELECTIVE,CS|CV")).to.deep.equal([
      {
        courseCode: "CSL55",
        subjectName: "Machine Learning",
        semester: 5,
        credits: 3,
        subjectType: "ELECTIVE",
        eligibleDeptCodes: ["CS", "CV"],
      },
    ]);
  });

  it("skips the header line when present, and only then", () => {
    const withHeader = parseSubjectCsv("courseCode,subjectName,semester,credits\nCSL44,DS,4,4");
    const without = parseSubjectCsv("CSL44,DS,4,4");
    expect(withHeader).to.have.length(1);
    expect(without).to.have.length(1);
    expect(withHeader[0].courseCode).to.eq("CSL44");
  });

  it("leaves blank numeric cells null rather than 0", () => {
    // 0 would file the subject under semester 0 silently; null makes the server say it is required
    const [row] = parseSubjectCsv("CSL44,DS,,");
    expect(row.semester).to.eq(null);
    expect(row.credits).to.eq(null);
  });

  it("gives a REGULAR row an empty dept-code list", () => {
    const [row] = parseSubjectCsv("CSL44,Data Structures,4,4,REGULAR,");
    expect(row.eligibleDeptCodes).to.deep.equal([]);
  });

  it("parses its own template, commas and all", () => {
    // the template ships to admins as the worked example, so it must survive its own parser
    const rows = parseSubjectCsv(SUBJECT_CSV_TEMPLATE);
    expect(rows).to.have.length(2);
    expect(rows[1].subjectName).to.eq("Design, Analysis of Algorithms");
    expect(rows[1].eligibleDeptCodes).to.deep.equal(["CS", "CV"]);
  });
});

describe("parseStudentCsv", () => {
  // Six columns read POSITIONALLY, so a column-order slip is silent: every field still parses, just
  // into the wrong key. students.cy.js asserts the request body once through the DOM, which cannot
  // distinguish that from a correct parse — hence asserting the mapping directly here.
  it("maps the six columns in header order", () => {
    expect(parseStudentCsv("1MS24CS001,Asha Rao,2006-04-12,9999999999,2,1")).to.deep.equal([
      {
        rollNo: "1MS24CS001",
        name: "Asha Rao",
        dateOfBirth: "2006-04-12",
        phone: "9999999999",
        currentSemester: 2,
        entrySemester: 1,
      },
    ]);
  });

  it("leaves a blank phone and blank semesters null so the batch defaults apply", () => {
    const [row] = parseStudentCsv("1MS24CS002,Migrant Kid,2005-09-01,,,");
    expect(row.phone).to.eq(null);
    expect(row.currentSemester).to.eq(null);
    expect(row.entrySemester).to.eq(null);
  });

  it("strips non-digits from the phone and caps it at ten", () => {
    const [row] = parseStudentCsv("1MS24CS001,Asha Rao,2006-04-12,+91 99999-99999123,2,1");
    expect(row.phone).to.eq("9199999999");
  });

  it("skips only a leading header row, never a data row", () => {
    // the previous filter dropped EVERY row whose first cell started with "usn"
    expect(parseStudentCsv(STUDENT_CSV_TEMPLATE)).to.have.length(2);
    expect(parseStudentCsv("1MS24CS001,Asha Rao,2006-04-12,9999999999,2,1")).to.have.length(1);
  });

  it("keeps a quoted comma inside a student name", () => {
    const [row] = parseStudentCsv('1MS24CS001,"Rao, Asha",2006-04-12,9999999999,2,1');
    expect(row.name).to.eq("Rao, Asha");
    expect(row.dateOfBirth).to.eq("2006-04-12");
  });
});
