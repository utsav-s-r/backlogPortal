package com.college.backlog.service;

import com.college.backlog.model.BatchLine;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.TimeZone;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the one place iText is used. Pure unit test — no Spring context, no DB.
 * Exists because the iText/BouncyCastle upgrade had no safety net: asserting on the EXTRACTED TEXT
 * (not the bytes, which carry a per-run document id) is what makes a version bump verifiable.
 */
class PdfServiceTest {

    private final PdfService service = new PdfService();

    private TimeZone defaultZone;

    /**
     * Forces the JVM default to UTC, which is what Render runs. Without it a
     * {@code ZoneId.systemDefault()} implementation would pass on any machine already in IST —
     * i.e. every developer's — and fail only in production, which is exactly how the original bug
     * survived.
     */
    @BeforeEach
    void pretendToBeTheProductionServer() {
        defaultZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
    }

    @AfterEach
    void restoreTheDefaultZone() {
        TimeZone.setDefault(defaultZone);
    }

    private static Registration sampleRegistration() {
        Student student = new Student();
        student.setRollNo("1MS22CS001");
        student.setName("Asha Rao");
        student.setEmail("1MS22CS001@msrit.edu");
        student.setPhone("9999912345");
        student.setYearOfJoining(2022);
        student.setCurrentSemester(5);
        student.setBranch("CS");

        Subject s1 = new Subject();
        s1.setId(1L);
        s1.setSubjectName("Data Structures");
        s1.setCourseCode("22CSL44");
        s1.setSemester(4);
        s1.setCredits(4);
        s1.setAcademicYearOffered(2022);

        Subject s2 = new Subject();
        s2.setId(2L);
        s2.setSubjectName("Operating Systems");
        s2.setCourseCode("22CS53");
        s2.setSemester(3);
        s2.setCredits(3);
        s2.setAcademicYearOffered(2022);

        Registration reg = new Registration();
        reg.setRegId("REG-TEST-0001");
        reg.setStudent(student);
        reg.setSubjects(List.of(s1, s2));
        // 21:00 UTC on the 10th is 02:30 IST on the 11th. Deliberate: the form must print the
        // college's date (11/08/2026), and every wrong implementation — the old LocalDateTime, or
        // a systemDefault() zone under the UTC default this class forces — prints the 10th.
        reg.setRegisteredAt(Instant.parse("2026-08-10T21:00:00Z"));
        // The cycle's month (June) deliberately differs from the month of registration (August):
        // the form used to print the latter, so equal values would let that bug pass.
        ExamCycle cycle = new ExamCycle("June 2026 Backlog Exams", "2026-06");
        cycle.setBatchLines(List.of(
                new BatchLine("B.E. I to VII Semester", "2021"),
                new BatchLine("M.TECH./MBA/MCA/M.ARCH. I to IV Semester", "2022 & 2023")));
        reg.setExamCycle(cycle);
        // The form reads the SNAPSHOT, never the live student row — snap_name/semester/
        // year_of_joining/branch are NOT NULL as of V7, and the fallbacks that used to read the
        // live row are gone (they printed today's values on an old registration).
        reg.setSnapName("Asha Rao");
        reg.setSnapSemester(5);
        reg.setSnapYearOfJoining(2022);
        reg.setSnapBranch("CS");
        return reg;
    }

    private static String textOf(byte[] pdf) throws Exception {
        try (PdfDocument doc = new PdfDocument(new PdfReader(new ByteArrayInputStream(pdf)))) {
            StringBuilder sb = new StringBuilder();
            for (int page = 1; page <= doc.getNumberOfPages(); page++) {
                sb.append(PdfTextExtractor.getTextFromPage(doc.getPage(page)));
            }
            return sb.toString();
        }
    }

    @Test
    void producesAWellFormedPdf() throws Exception {
        byte[] pdf = service.generateRegistrationPdf(sampleRegistration());

        assertThat(pdf).isNotEmpty();
        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(new String(pdf, StandardCharsets.ISO_8859_1)).endsWith("%%EOF\n");
        // a blank/failed render collapses to a couple of hundred bytes
        assertThat(pdf.length).isGreaterThan(2_000);
    }

    @Test
    void rendersTheStudentAndSubjectDataOntoThePage() throws Exception {
        String text = textOf(service.generateRegistrationPdf(sampleRegistration()));

        assertThat(text).contains("1MS22CS001");
        assertThat(text).contains("ASHA RAO");           // the form upper-cases the name
        assertThat(text).contains("22CSL44", "22CS53");
        assertThat(text).contains("Data Structures", "Operating Systems");
    }

    @Test
    void printsTheSnapshotSemesterNotTheBacklogSemester() throws Exception {
        // snapSemester is the student's CURRENT semester at registration time (5 here), never the
        // semester of the backlog subject being registered (4 and 3). Regression guard: the form
        // used to print the backlog semester.
        Registration reg = sampleRegistration();
        String text = textOf(service.generateRegistrationPdf(reg));

        int labelAt = text.indexOf("CURRENT SEMESTER");
        assertThat(labelAt).isGreaterThan(-1);
        assertThat(text.substring(labelAt, Math.min(labelAt + 60, text.length()))).contains("5");
    }

    // ---- the date line: the college's calendar day, not the server's ----

    @Test
    void printsTheRegistrationDateInTheCollegesZone() throws Exception {
        String text = textOf(service.generateRegistrationPdf(sampleRegistration()));

        assertThat(text).contains("11/08/2026");
        // The bug: 21:00 UTC on the 10th printed as the 10th, so anything registered between
        // 00:00 and 05:30 IST was dated a day early on a form that gets signed.
        assertThat(text).doesNotContain("10/08/2026");
    }

    // ---- batch list: the CYCLE's lines, not eight years hardcoded here ----

    @Test
    void printsTheCyclesBatchLinesWithTheirPunctuationSuppliedByTheForm() throws Exception {
        String text = textOf(service.generateRegistrationPdf(sampleRegistration()));

        // The stored halves plus the chrome the renderer owns — "(", " Batch Students)".
        assertThat(text).contains("B.E. I to VII Semester (2021 Batch Students)");
        // Free text, so a batch is not always one year.
        assertThat(text).contains("M.TECH./MBA/MCA/M.ARCH. I to IV Semester (2022 & 2023 Batch Students)");
    }

    /** The years that used to be compiled in. A cycle carrying two lines must print two — not
     *  these — or the hardcoded block is still in there somewhere. */
    @Test
    void printsNoBatchLineTheCycleDoesNotCarry() throws Exception {
        String text = textOf(service.generateRegistrationPdf(sampleRegistration()));

        // Not 2023: this fixture's own PG line ends "2022 & 2023 Batch Students", so asserting
        // its absence would fail on the fixture rather than on the hardcoded block.
        assertThat(text).doesNotContain("2024 Batch Students");
        assertThat(text).doesNotContain("2025 Batch Students");
        assertThat(text).doesNotContain("B.Arch. I to VIII Semester");
    }

    /** Informational section: no lines means no block, never a refusal — a student must still be
     *  able to print their form. */
    @Test
    void omitsTheBatchBlockWhenTheCycleHasNoLines() throws Exception {
        Registration reg = sampleRegistration();
        reg.getExamCycle().setBatchLines(List.of());

        String text = textOf(service.generateRegistrationPdf(reg));

        assertThat(text).doesNotContain("Batch Students");
        assertThat(text).contains("1MS22CS001");   // the rest of the form is intact
    }

    // ---- examination month: the CYCLE's, never the month the student happened to register in ----

    @Test
    void printsTheCyclesExamMonthNotTheRegistrationMonth() throws Exception {
        String text = textOf(service.generateRegistrationPdf(sampleRegistration()));

        int labelAt = text.indexOf("Examination Month");
        assertThat(labelAt).isGreaterThan(-1);
        String field = text.substring(labelAt, Math.min(labelAt + 80, text.length()));
        assertThat(field).contains("June 2026");
        // The regression itself: August is registeredAt's month.
        assertThat(field).doesNotContain("August");
    }

    /** Cycles created before the YYYY-MM rule hold free text. No rule recovers a month from it, so
     *  it prints verbatim — visibly wrong on the form, where a guessed month would not be. */
    @Test
    void printsALegacyFreeTextCycleMonthVerbatim() throws Exception {
        Registration reg = sampleRegistration();
        reg.setExamCycle(new ExamCycle("Testing", "Not a valid month/year"));

        assertThat(textOf(service.generateRegistrationPdf(reg))).contains("Not a valid month/year");
    }

    /** exam_cycle_id is NOT NULL in the schema, so this is a backstop, not a live case: the box is
     *  left blank and the form still renders, rather than falling back to the registration date. */
    @Test
    void leavesTheExamMonthBlankWhenTheRegistrationHasNoCycle() throws Exception {
        Registration reg = sampleRegistration();
        reg.setExamCycle(null);

        String text = textOf(service.generateRegistrationPdf(reg));

        assertThat(text).contains("Examination Month");
        assertThat(text).doesNotContain("August 2026").doesNotContain("June 2026");
    }

    @Test
    void survivesARegistrationWithNoSubjects() throws Exception {
        Registration reg = sampleRegistration();
        reg.setSubjects(List.of());

        byte[] pdf = service.generateRegistrationPdf(reg);

        assertThat(new String(pdf, 0, 5, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF-");
        assertThat(textOf(pdf)).contains("1MS22CS001");
    }

    // ---- required identity fields: refuse rather than print a blank on a form that gets signed ----

    private static Registration withStudent(Student s) {
        Registration reg = sampleRegistration();
        reg.setStudent(s);
        return reg;
    }

    @Test
    void refusesTheFormWhenTheNameIsMissing() {
        // the whole point: this used to render a form with an empty Name box, which a student could
        // print, get signed by their proctor and HOD, and submit
        Registration reg = sampleRegistration();
        reg.setSnapName(null); // V7 makes this unreachable in the DB; the guard stays as a backstop

        assertThatThrownBy(() -> service.generateRegistrationPdf(reg))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT))
                .hasMessageContaining("name")
                .hasMessageContaining("contact the department office");
    }

    @Test
    void refusesTheFormWhenTheBranchIsMissing() {
        // and never prints the literal "B.E. / null", which string concat produced before the
        // prefix was moved after the check
        Registration reg = sampleRegistration();
        reg.setSnapBranch(null);

        assertThatThrownBy(() -> service.generateRegistrationPdf(reg))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("branch");
    }

    @Test
    void refusesTheFormWhenTheStudentRowIsUnreadable() {
        // registrations.roll_no is nullable at the DB, so an orphan row is reachable; it used to be
        // swallowed into blank identity cells
        Registration reg = sampleRegistration();
        reg.setStudent(null);

        assertThatThrownBy(() -> service.generateRegistrationPdf(reg))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("contact the department office");
    }

    @Test
    void stillRendersWhenOnlyTheOptionalContactFieldsAreMissing() throws Exception {
        // phone is optional by design and the printed email derives from the USN — neither
        // invalidates the form, so these must NOT refuse the download
        Student noContact = new Student();
        noContact.setRollNo("1MS22CS001");
        noContact.setName("Asha Rao");
        noContact.setEmail(null);   // explicit: the point of this case
        noContact.setPhone(null);
        noContact.setYearOfJoining(2022);
        noContact.setCurrentSemester(5);
        noContact.setBranch("CS");

        Registration reg = withStudent(noContact);
        reg.setSnapEmail(null);
        reg.setSnapPhone(null);

        String text = textOf(service.generateRegistrationPdf(reg));

        assertThat(text).contains("1MS22CS001", "ASHA RAO");
        assertThat(text).contains("B.E. / CS");
        assertThat(text).contains("1ms22cs001@msrit.edu");
    }

    @Test
    void printsTheCollegeEmailNeverTheStudentEditedOne() throws Exception {
        Registration reg = sampleRegistration();
        reg.getStudent().setEmail("asha.personal@gmail.com");
        reg.setSnapEmail("asha.personal@gmail.com");

        String text = textOf(service.generateRegistrationPdf(reg));

        assertThat(text).contains("1ms22cs001@msrit.edu");
        assertThat(text).doesNotContain("asha.personal@gmail.com");
    }

    @Test
    void nullRegistrationIsAProgrammingErrorNotAStudentFacingConflict() {
        assertThatThrownBy(() -> service.generateRegistrationPdf(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---- summary report: one broken row must not cost the other rows, but must look broken ----

    @Test
    void summaryMarksAnUnreadableRowAndKeepsTheGoodOnes() throws Exception {
        Registration broken = sampleRegistration();
        broken.setStudent(null); // NPE inside the cell accessor
        Registration ok = sampleRegistration();

        byte[] pdf = service.generateRegistrationsSummaryPdf(List.of(broken, ok), Map.of());
        String text = textOf(pdf);

        // the good row survives — a bulk report must not be lost to one bad row
        assertThat(text).contains("1MS22CS001");
        // and the bad one reads as broken rather than as a student with no name
        assertThat(text).contains("!! unavailable");
    }
}
