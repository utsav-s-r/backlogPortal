// parseAcademicYear, asserted directly rather than through the DOM.
//
// Worth testing this way: the failure mode is SILENT. A parser that reads only the leading four
// digits turns "2025-24" or "20251" into 2025, and the Semesters panel saves that as the year the
// student studied the semester — the year-binding key. Nothing on screen distinguishes it from a
// correct save. Mutation-tested: restoring `match(/^(\d{4})/)` must fail the rejection cases.
import { formatAcademicYear, parseAcademicYear } from "../../src/lib/academicYear";

describe("parseAcademicYear", () => {
  it("reads a bare start year, as a string or a number", () => {
    expect(parseAcademicYear("2025")).to.equal(2025);
    expect(parseAcademicYear(2025)).to.equal(2025);
    expect(parseAcademicYear("  2025 ")).to.equal(2025);
  });

  it("reads a span whose end is the start plus one", () => {
    expect(parseAcademicYear("2025-26")).to.equal(2025);
    expect(parseAcademicYear("2025-2026")).to.equal(2025);
    expect(parseAcademicYear("2025–26")).to.equal(2025); // en dash
    expect(parseAcademicYear("2025/26")).to.equal(2025);
    expect(parseAcademicYear("2025 - 26")).to.equal(2025);
  });

  it("handles the century rollover in a two-digit end", () => {
    expect(parseAcademicYear("2099-00")).to.equal(2099);
    expect(parseAcademicYear("2099-2100")).to.equal(2099);
  });

  it("round-trips everything formatAcademicYear produces", () => {
    [2019, 2024, 2025, 2099].forEach((y) => {
      expect(parseAcademicYear(formatAcademicYear(y))).to.equal(y);
    });
  });

  it("rejects a span whose end is not the start plus one", () => {
    expect(parseAcademicYear("2025-24")).to.be.NaN;
    expect(parseAcademicYear("2025-25")).to.be.NaN;
    expect(parseAcademicYear("2024-26")).to.be.NaN;
    expect(parseAcademicYear("2025-2027")).to.be.NaN;
  });

  it("rejects anything that is not exactly a year or a span", () => {
    expect(parseAcademicYear("20251")).to.be.NaN;
    expect(parseAcademicYear("2025-26x")).to.be.NaN;
    expect(parseAcademicYear("2025-2")).to.be.NaN;
    expect(parseAcademicYear("25-26")).to.be.NaN;
    expect(parseAcademicYear("AY 2025-26")).to.be.NaN;
  });

  it("treats empty and missing input as not provided", () => {
    expect(parseAcademicYear("")).to.be.NaN;
    expect(parseAcademicYear("   ")).to.be.NaN;
    expect(parseAcademicYear(null)).to.be.NaN;
    expect(parseAcademicYear(undefined)).to.be.NaN;
  });
});
