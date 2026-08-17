package com.college.backlog.service;

import com.college.backlog.model.Registration;
import com.college.backlog.model.Subject;
import com.itextpdf.io.font.constants.StandardFonts;
import com.itextpdf.io.image.ImageDataFactory;
import com.itextpdf.kernel.colors.DeviceRgb;
import com.itextpdf.kernel.font.PdfFont;
import com.itextpdf.kernel.font.PdfFontFactory;
import com.itextpdf.kernel.geom.PageSize;
import com.itextpdf.kernel.pdf.PdfDocument;
import com.itextpdf.kernel.pdf.PdfWriter;
import com.itextpdf.layout.Document;
import com.itextpdf.layout.borders.Border;
import com.itextpdf.layout.borders.SolidBorder;
import com.itextpdf.layout.element.*;
import com.itextpdf.layout.properties.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class PdfService {

    private static final Logger log = LoggerFactory.getLogger(PdfService.class);

    private static final DeviceRgb BLACK = new DeviceRgb(0, 0, 0);

    private static final float FS_AFFILIATION = 7.0f;
    private static final float FS_DATE        = 8.5f;
    private static final float FS_TITLE       = 10.5f;
    private static final float FS_FOR         = 8.5f;
    private static final float FS_BATCH       = 8.5f;
    private static final float FS_SECTION_HDR = 9.0f;
    private static final float FS_FIELD_LABEL = 8.5f;
    private static final float FS_TABLE_HDR   = 7.5f;
    private static final float FS_TABLE_DATA  = 7.5f;
    private static final float FS_SIG         = 8.5f;
    private static final float FS_NOTE        = 8.0f;

    // ---- student registration form ----
    public byte[] generateRegistrationPdf(Registration reg) throws Exception {
        // Only ever called with a registration the controller already loaded and ownership-checked,
        // so null is a programming error, not the 409 a student should be shown.
        if (reg == null) {
            throw new IllegalArgumentException("Cannot render a registration form for a null registration");
        }

        ByteArrayOutputStream baos   = new ByteArrayOutputStream();
        PdfWriter             writer = new PdfWriter(baos);
        PdfDocument           pdfDoc = new PdfDocument(writer);
        Document              doc    = new Document(pdfDoc, PageSize.A4);

        doc.setMargins(25f, 28f, 25f, 28f);

        PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);
        PdfFont bold    = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);

        addHeader(doc, regular, bold);
        addHorizontalRule(doc);
        addDateLine(doc, regular, bold, reg);
        addMainTitle(doc, bold);
        addBatchList(doc, regular, bold);
        addCurrentSemesterLabel(doc, bold, regular, reg);
        addStudentDetailsTable(doc, regular, bold, reg);
        addSubjectsTable(doc, regular, bold, reg);
        addSignatureRow(doc, regular, bold);
        addNote(doc, regular, bold);

        doc.close();
        return baos.toByteArray();
    }

    // ---- admin summary export ----
    // Returns the finished bytes rather than writing to the response stream: the caller must be
    // able to fail with an error status, and once PDF headers are committed a mid-generation throw
    // appends the error JSON into the file. Summary rows are small, and generatePdf above already
    // buffers the same way. Runs synchronously on the request thread; the rows arrive fully fetched
    // (findAll(spec, Sort) entity-graphs `subjects`), so nothing here lazy-loads — required, since
    // open-in-view is off.
    //
    // `context` is an ordered label -> value record of what the report covers (cycle, department,
    // semester, status, row count). Printed under the title: without it two exports of different
    // filters are indistinguishable on paper, and these get signed and filed.
    public byte[] generateRegistrationsSummaryPdf(List<Registration> registrations,
                                                  java.util.Map<String, String> context) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PdfWriter writer = new PdfWriter(baos);
        PdfDocument pdfDoc = new PdfDocument(writer);

        // doc.close() still flushes+closes the writer/pdfDoc it owns; t-w-r only adds the failure
        // path: close() runs on a throw and its own failure is suppressed, so the original wins.
        try (Document doc = new Document(pdfDoc, PageSize.A4.rotate())) { // landscape fits the columns
            doc.setMargins(25f, 25f, 25f, 25f);

            PdfFont bold = PdfFontFactory.createFont(StandardFonts.HELVETICA_BOLD);
            PdfFont regular = PdfFontFactory.createFont(StandardFonts.HELVETICA);

            Paragraph title = new Paragraph("Registrations Summary Report")
                    .setFont(bold).setFontSize(14f)
                    .setTextAlignment(TextAlignment.CENTER)
                    .setMarginBottom(6f);
            doc.add(title);
            addSummaryContext(doc, regular, bold, context);

            float[] columnWidths = { 4f, 13f, 19f, 5f, 34f, 10f, 12f };
            Table table = new Table(UnitValue.createPercentArray(columnWidths)).useAllAvailableWidth();

            // Status is always printed, even when the whole report is one status: a selection can
            // mix statuses, and an exam list that silently contains unverified students is worse
            // than a redundant column.
            String[] headers = { "Sl.", "USN", "Name", "Sem", "Subjects", "Status", "Date" };
            for (String h : headers) {
                table.addHeaderCell(new Cell().add(new Paragraph(h).setFont(bold).setFontSize(9f))
                        .setBackgroundColor(new DeviceRgb(230, 230, 230))
                        .setTextAlignment(TextAlignment.CENTER)
                        .setVerticalAlignment(VerticalAlignment.MIDDLE));
            }

            DateTimeFormatter dtf = DateTimeFormatter.ofPattern("dd/MM/yyyy");
            int i = 1;
            for (Registration reg : registrations) {
                table.addCell(dataCellCentre(String.valueOf(i++), regular));
                // cell(), not required(): this is a bulk report — one broken row must not cost the
                // admin the other rows, but it must render as visibly broken rather than empty
                table.addCell(dataCellCentre(cell(reg, "rollNo", r -> r.getStudent().getRollNo()), regular));
                table.addCell(dataCell(cell(reg, "name", Registration::getSnapName), regular));
                table.addCell(dataCellCentre(cell(reg, "semester",
                        r -> String.valueOf(r.getSnapSemester())), regular));

                // course code included per subject — it's the canonical identifier
                String subjectsStr = reg.getSubjects() != null
                        ? reg.getSubjects().stream()
                            .map(s -> (s.getCourseCode() != null ? s.getCourseCode() + " " : "") + s.getSubjectName())
                            .collect(Collectors.joining(", "))
                        : "";
                table.addCell(dataCell(subjectsStr, regular));

                table.addCell(dataCellCentre(
                        reg.getStatus() != null ? reg.getStatus().name() : "", regular));

                String dateStr = reg.getRegisteredAt() != null ? reg.getRegisteredAt().format(dtf) : "";
                table.addCell(dataCellCentre(dateStr, regular));
            }

            doc.add(table);
        }
        return baos.toByteArray();
    }

    /** Label: value line under the title, saying exactly which rows the report covers. */
    private void addSummaryContext(Document doc, PdfFont regular, PdfFont bold,
                                   java.util.Map<String, String> context) {
        if (context == null || context.isEmpty()) {
            return;
        }
        Paragraph line = new Paragraph().setFontSize(8.5f)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(12f);
        boolean first = true;
        for (java.util.Map.Entry<String, String> e : context.entrySet()) {
            if (!first) {
                line.add(new Text("   |   ").setFont(regular));
            }
            line.add(new Text(e.getKey() + ": ").setFont(bold));
            line.add(new Text(String.valueOf(e.getValue())).setFont(regular));
            first = false;
        }
        doc.add(line);
    }

    // ---- 1. header ----
    private void addHeader(Document doc, PdfFont regular, PdfFont bold) throws Exception {

        Table header = new Table(UnitValue.createPercentArray(new float[]{42f, 58f}))
                .useAllAvailableWidth()
                .setBorder(Border.NO_BORDER)
                .setMarginBottom(0f);

        Cell logoCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPadding(0f);

        byte[] logoBytes = loadLogoBytes();
        if (logoBytes != null) {
            Image logo = new Image(ImageDataFactory.create(logoBytes))
                    .setWidth(160f)
                    .setAutoScale(false);
            logoCell.add(logo);
        } else {
            Table logoBox = new Table(1).useAllAvailableWidth();
            Cell  inner   = new Cell()
                    .setBorder(new SolidBorder(BLACK, 0.6f))
                    .setPadding(5f);
            inner.add(new Paragraph("RAMAIAH")
                    .setFont(bold).setFontSize(13f)
                    .setTextAlignment(TextAlignment.CENTER).setMargin(0));
            inner.add(new Paragraph("Institute of Technology")
                    .setFont(regular).setFontSize(8f)
                    .setTextAlignment(TextAlignment.CENTER).setMargin(0));
            logoBox.addCell(inner);
            logoCell.add(logoBox);
        }

        Cell affCell = new Cell()
                .setBorder(Border.NO_BORDER)
                .setVerticalAlignment(VerticalAlignment.MIDDLE)
                .setPaddingTop(4f);

        for (String line : new String[]{
                "(Autonomous Institute, Affiliated to VTU)",
                "(Approved by AICTE, New Delhi & Govt. of Karnataka)",
                "Accredited by NBA & NAAC with \u2018A+\u2019 Grade"}) {
            affCell.add(new Paragraph(line)
                    .setFont(regular).setFontSize(FS_AFFILIATION)
                    .setTextAlignment(TextAlignment.RIGHT)
                    .setMargin(0).setPaddingBottom(1f));
        }

        header.addCell(logoCell);
        header.addCell(affCell);
        doc.add(header);
    }

    // ---- horizontal rule ----
    private void addHorizontalRule(Document doc) {
        Table rule = new Table(UnitValue.createPercentArray(new float[]{100f}))
                .useAllAvailableWidth()
                .setBorder(Border.NO_BORDER)
                .setBorderBottom(new SolidBorder(BLACK, 0.8f))
                .setMarginTop(2f)
                .setMarginBottom(3f);
        rule.addCell(new Cell()
                .add(new Paragraph("").setFontSize(1f))
                .setBorder(Border.NO_BORDER)
                .setPadding(0f));
        doc.add(rule);
    }

    // ---- 2. date line ----
    private void addDateLine(Document doc, PdfFont regular, PdfFont bold,
                             Registration reg) {
        String date = (reg != null && reg.getRegisteredAt() != null)
                ? reg.getRegisteredAt().format(DateTimeFormatter.ofPattern("dd/MM/yyyy"))
                : "................";

        Paragraph p = new Paragraph()
                .setFontSize(FS_DATE)
                .setTextAlignment(TextAlignment.RIGHT)
                .setMarginBottom(2f);
        p.add(new Text("Date : ").setFont(bold));
        p.add(new Text(date).setFont(regular));
        doc.add(p);
    }

    // ---- 3. main title ----
    private void addMainTitle(Document doc, PdfFont bold) {
        doc.add(new Paragraph("EXAM REGISTRATION FORM FOR  BACKLOG SUBJECT EXAMINATIONS")
                .setFont(bold).setFontSize(FS_TITLE)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(0f).setMarginTop(1f));

        doc.add(new Paragraph("FOR")
                .setFont(bold).setFontSize(FS_FOR)
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginBottom(1f).setMarginTop(0f));
    }

    // ---- 4. batch list ----
    private void addBatchList(Document doc, PdfFont regular, PdfFont bold) {

        String[][] rows = {
            { "B.E. I to VII Semester ",                    "2021", " Batch Students)"        },
            { "B.Arch. I to VIII Semester ",                "2021", " Batch Students)"        },
            { "B.E. / B.Arch. I to VII Semester ",          "2022", " Batch Students)"        },
            { "B.E. / B.Arch. I to V Semester ",            "2023", " Batch Students)"        },
            { "B.E. / B.Arch. I & II Semester ",            "2024", " Batch Students)"        },
            { "B.E. / B.Arch. I Semester ",                 "2025", " Batch Students)"        },
            { "M.TECH./MBA/MCA/M.ARCH. I to IV Semester ",  "2022 & 2023", " Batch Students)" },
            { "M.TECH./MBA/MCA/M.ARCH. I to III Semester ", "2024", " Batch Students)"        },
        };

        for (String[] row : rows) {
            Paragraph p = new Paragraph()
                    .setTextAlignment(TextAlignment.CENTER)
                    .setFontSize(FS_BATCH)
                    .setMargin(0).setPaddingBottom(0.8f);
            p.add(new Text(row[0]).setFont(bold));
            p.add(new Text("(").setFont(bold));
            p.add(new Text(row[1]).setFont(bold));
            p.add(new Text(row[2]).setFont(regular));
            doc.add(p);
        }

        doc.add(new Paragraph(" ").setFontSize(2f));
    }

    // ---- 5. current semester label ----
    private void addCurrentSemesterLabel(Document doc, PdfFont bold, PdfFont regular,
                                         Registration reg) {
        // Required like the identity fields: this is what the exam section reads to decide which
        // paper the student sits. The old dotted fallback printed a fill-in-by-hand blank, which
        // is precisely the silent-blank failure being removed.
        String sem = required(reg, "current semester", r -> String.valueOf(r.getSnapSemester()));

        Paragraph p = new Paragraph()
                .setFontSize(FS_SECTION_HDR)
                .setMarginBottom(2f).setMarginTop(1f);
        p.add(new Text("CURRENT SEMESTER OF THE STUDENT : ").setFont(bold));
        p.add(new Text(sem).setFont(regular));
        doc.add(p);
    }

    // ---- 6. student details table ----
    private void addStudentDetailsTable(Document doc, PdfFont regular, PdfFont bold,
                                        Registration reg) {

        String examMonthYear = (reg != null && reg.getRegisteredAt() != null)
                ? reg.getRegisteredAt().format(DateTimeFormatter.ofPattern("MMMM yyyy")) : "";
        // identity — the form means nothing without these, so a gap refuses the download
        String name   = required(reg, "name", r -> upper(r.getSnapName()));
        String usn    = required(reg, "USN", r -> r.getStudent().getRollNo());
        // the prefix is added AFTER the check: concatenating first turned a missing branch into the
        // literal "B.E. / null" on the printed form, which no exception and no null check could see
        String branch = "B.E. / " + required(reg, "branch", Registration::getSnapBranch);
        // contact details — a blank line here is fillable by hand and phone is optional by design
        String email  = optional(reg, "email", r ->
                r.getSnapEmail() != null ? r.getSnapEmail() : r.getStudent().getEmail());
        String mobile = optional(reg, "mobile", r ->
                r.getSnapPhone() != null ? r.getSnapPhone() : r.getStudent().getPhone());

        String[][] fields = {
            { "Examination Month / Year",                  examMonthYear },
            { "Name of the Student (In CAPITAL letters)",  name          },
            { "USN",                                       usn           },
            { "Program / Branch",                          branch        },
            { "Email ID",                                  email         },
            { "Mobile No.",                                mobile        },
        };

        Table t = new Table(UnitValue.createPercentArray(new float[]{40f, 60f}))
                .useAllAvailableWidth()
                .setBorder(new SolidBorder(BLACK, 0.7f))
                .setMarginBottom(4f);

        for (int i = 0; i < fields.length; i++) {
            boolean lastRow   = (i == fields.length - 1);
            Border  rowBottom = lastRow ? Border.NO_BORDER : new SolidBorder(BLACK, 0.4f);

            t.addCell(new Cell()
                    .add(new Paragraph(fields[i][0])
                            .setFont(regular).setFontSize(FS_FIELD_LABEL).setMargin(2.5f))
                    .setBorderLeft(Border.NO_BORDER).setBorderTop(Border.NO_BORDER)
                    .setBorderBottom(rowBottom)
                    .setBorderRight(new SolidBorder(BLACK, 0.5f)));

            t.addCell(new Cell()
                    .add(new Paragraph(fields[i][1])
                            .setFont(regular).setFontSize(FS_FIELD_LABEL).setMargin(2.5f))
                    .setBorderLeft(Border.NO_BORDER).setBorderTop(Border.NO_BORDER)
                    .setBorderBottom(rowBottom)
                    .setBorderRight(Border.NO_BORDER));
        }

        doc.add(t);
    }

    // ---- 7. subjects table (courseCode + credits from Subject) ----
    private void addSubjectsTable(Document doc, PdfFont regular, PdfFont bold,
                                  Registration reg) {

        float[] cols = { 5f, 6f, 12f, 37f, 9f, 31f };

        Table t = new Table(UnitValue.createPercentArray(cols))
                .useAllAvailableWidth()
                .setBorder(new SolidBorder(BLACK, 0.7f))
                .setMarginBottom(4f);

        // title row
        t.addCell(new Cell(1, 6)
                .add(new Paragraph("SUBJECTS REGISTERED FOR BACKLOG SUBJECT EXAMS")
                        .setFont(bold).setFontSize(FS_SECTION_HDR)
                        .setTextAlignment(TextAlignment.CENTER).setMargin(3f))
                .setBorder(new SolidBorder(BLACK, 0.7f)));

        // column headers
        for (String h : new String[]{
                "Sl.\nNo.", "Sem.", "Course\nCode", "Course Title",
                "Credits", "Remarks"}) {
            t.addCell(new Cell()
                    .add(new Paragraph(h)
                            .setFont(bold).setFontSize(FS_TABLE_HDR)
                            .setTextAlignment(TextAlignment.CENTER).setMargin(2f))
                    .setBorder(new SolidBorder(BLACK, 0.5f))
                    .setVerticalAlignment(VerticalAlignment.MIDDLE));
        }

        // data rows
        List<Subject> subjects = (reg != null && reg.getSubjects() != null)
                ? reg.getSubjects() : List.of();

        int totalCredits = 0;

        for (int i = 0; i < subjects.size(); i++) {
            Subject s = subjects.get(i);

            // credits — "Audit" subjects have 0 credits in the model, display "Audit"
            String creditsDisplay = s.getCredits() == 0 ? "Audit" : String.valueOf(s.getCredits());
            if (s.getCredits() > 0) totalCredits += s.getCredits();

            // Sl. No — centred
            t.addCell(new Cell()
                    .add(new Paragraph(String.valueOf(i + 1))
                            .setFont(regular).setFontSize(FS_TABLE_DATA)
                            .setTextAlignment(TextAlignment.CENTER).setMargin(1.5f))
                    .setBorder(new SolidBorder(BLACK, 0.4f))
                    .setMinHeight(13f));

            t.addCell(dataCell(String.valueOf(s.getSemester()), regular));
            t.addCell(dataCell(nvl(s.getCourseCode()), regular));
            t.addCell(dataCell(nvl(s.getSubjectName()), regular));
            t.addCell(dataCellCentre(creditsDisplay, regular));
            t.addCell(dataCell("", regular)); // remarks
        }

        // total row
        t.addCell(new Cell(1, 4)
                .add(new Paragraph("Total")
                        .setFont(bold).setFontSize(FS_TABLE_DATA)
                        .setTextAlignment(TextAlignment.RIGHT).setMargin(2f))
                .setBorder(new SolidBorder(BLACK, 0.4f)));
        t.addCell(new Cell()
                .add(new Paragraph(totalCredits > 0 ? String.valueOf(totalCredits) : "")
                        .setFont(bold).setFontSize(FS_TABLE_DATA)
                        .setTextAlignment(TextAlignment.CENTER).setMargin(2f))
                .setBorder(new SolidBorder(BLACK, 0.4f)));
        t.addCell(new Cell().add(new Paragraph("").setMargin(2f))
                .setBorder(new SolidBorder(BLACK, 0.4f)));

        doc.add(t);
    }

    // ---- 8. signature row ----
    private void addSignatureRow(Document doc, PdfFont regular, PdfFont bold) {

        Table t = new Table(UnitValue.createPercentArray(new float[]{33.33f, 33.33f, 33.33f}))
                .useAllAvailableWidth()
                .setBorder(Border.NO_BORDER)
                .setMarginTop(30f).setMarginBottom(10f);

        for (String role : new String[]{ "Student", "Proctor", "HOD" }) {
            Paragraph p = new Paragraph()
                    .setTextAlignment(TextAlignment.CENTER).setFontSize(FS_SIG);
            p.add(new Text("Signature of the ").setFont(regular));
            p.add(new Text(role).setFont(bold));
            p.add(new Text("\nwith date").setFont(regular));
            t.addCell(new Cell().setBorder(Border.NO_BORDER).add(p));
        }

        doc.add(t);
    }

    // ---- 9. note ----
    private void addNote(Document doc, PdfFont regular, PdfFont bold) {
        Paragraph note = new Paragraph().setFontSize(FS_NOTE).setMarginTop(2f);
        note.add(new Text("Note : ").setFont(bold));
        note.add(new Text(
                "Student shall submit the duly signed copy of this form to the "
                + "Department Office and Proctor").setFont(regular));
        doc.add(note);
    }

    // ---- helpers ----

    /** Missing logo degrades to the text header rather than failing the form — but say so, or
     *  every form ships without the institutional logo indefinitely and nobody finds out. */
    private byte[] loadLogoBytes() {
        try (InputStream is = getClass().getClassLoader()
                .getResourceAsStream("RitLogo.png")) {
            if (is == null) {
                log.warn("PDF_LOGO_MISSING resource=RitLogo.png — forms render without the logo");
                return null;
            }
            return is.readAllBytes();
        } catch (Exception e) {
            log.warn("PDF_LOGO_UNREADABLE resource=RitLogo.png", e);
            return null;
        }
    }

    private Cell dataCell(String text, PdfFont regular) {
        return new Cell()
                .add(new Paragraph(nvl(text))
                        .setFont(regular).setFontSize(FS_TABLE_DATA)
                        .setTextAlignment(TextAlignment.LEFT).setMargin(1.5f))
                .setBorder(new SolidBorder(BLACK, 0.4f))
                .setMinHeight(13f);
    }

    private Cell dataCellCentre(String text, PdfFont regular) {
        return new Cell()
                .add(new Paragraph(nvl(text))
                        .setFont(regular).setFontSize(FS_TABLE_DATA)
                        .setTextAlignment(TextAlignment.CENTER).setMargin(1.5f))
                .setBorder(new SolidBorder(BLACK, 0.4f))
                .setMinHeight(13f);
    }

    private String nvl(String s) { return s != null ? s : ""; }

    /** Null-safe upper-case, so a missing name is classified MISSING rather than surfacing as an
     *  NPE that reads like a code fault. */
    private String upper(String s) { return s != null ? s.toUpperCase() : null; }

    // ---- field access ----
    // Replaces a single `safe()` that caught Exception and returned "" for everything. That one
    // helper served two documents with opposite needs, so it had to pick the weaker: a student
    // could download a form with a blank USN, get it signed, and submit it. Split by document.

    /** Printed in a summary cell whose value couldn't be read — a blank cell reads as "this
     *  student has no name", which is exactly the silence this replaces. */
    private static final String CELL_UNAVAILABLE = "!! unavailable";

    /**
     * A field the student's form is invalid without (name, USN, branch, semester). Missing or
     * unreadable ⇒ refuse the download rather than print an empty box on a document that gets
     * signed and submitted.
     *
     * <p>409 + "contact the department office", not 500: the data is broken and a human has to fix
     * it, so telling the student to retry is useless. Mirrors the year-binding fail-closed wording
     * in RegistrationService/StudentController.
     */
    private String required(Registration reg, String field, Function<Registration, String> fn) {
        String value;
        try {
            value = fn.apply(reg);
        } catch (RuntimeException e) {
            // a throw here is a bug or a broken row, not a user error — keep the stack trace
            log.error("PDF_FIELD_UNREADABLE regId={} field={}", regIdOf(reg), field, e);
            throw incompleteForm(field);
        }
        if (value == null || value.isBlank()) {
            log.warn("PDF_FIELD_MISSING regId={} field={} (form refused)", regIdOf(reg), field);
            throw incompleteForm(field);
        }
        return value;
    }

    /** A field the form is still valid without (email, mobile — phone is optional by design).
     *  Absent prints blank; a THROW is still not swallowed, since that means something else broke. */
    private String optional(Registration reg, String field, Function<Registration, String> fn) {
        try {
            String v = fn.apply(reg);
            return v != null ? v : "";
        } catch (RuntimeException e) {
            log.warn("PDF_FIELD_UNREADABLE regId={} field={} (optional, left blank)",
                    regIdOf(reg), field, e);
            return "";
        }
    }

    /** Summary-report cell: one unreadable row must not lose the other rows, but it must LOOK
     *  broken rather than empty. Never throws — the report is bulk. */
    private String cell(Registration reg, String field, Function<Registration, String> fn) {
        try {
            String v = fn.apply(reg);
            return v != null ? v : "";
        } catch (RuntimeException e) {
            log.warn("PDF_SUMMARY_CELL_UNREADABLE regId={} field={}", regIdOf(reg), field, e);
            return CELL_UNAVAILABLE;
        }
    }

    private ResponseStatusException incompleteForm(String field) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
            "Your registration form is missing required details (" + field
                + "). Please contact the department office.");
    }

    private String regIdOf(Registration reg) {
        return reg != null ? reg.getRegId() : "null";
    }
}