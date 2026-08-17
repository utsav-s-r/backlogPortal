package com.college.backlog.service;

import com.college.backlog.model.Registration;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfReader;
import com.itextpdf.kernel.pdf.canvas.parser.PdfTextExtractor;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
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
        reg.setRegisteredAt(LocalDateTime.of(2026, 8, 10, 9, 30));
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
        // phone is optional by design and email is auto-assigned — neither invalidates the form,
        // so these must NOT refuse the download
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
