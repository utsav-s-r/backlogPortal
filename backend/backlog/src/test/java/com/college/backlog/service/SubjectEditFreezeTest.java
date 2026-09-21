package com.college.backlog.service;

import com.college.backlog.controller.AdminAuthorizationFixture;
import com.college.backlog.controller.dto.SubjectUpdateRequest;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A subject's name, code, semester and credits are NOT snapshotted onto a registration — the admin
 * table, the student's list and both PDFs read them live off the subject row. So editing one after
 * a student has registered rewrites the form that student already signed, with nothing failing and
 * nothing to notice. This pins the refusal.
 *
 * <p>Registrations are created HERE and never in {@link AdminAuthorizationFixture}: a registration
 * makes its student and subject undeletable (409), which would silently break the delete cases in
 * the suites that share that fixture.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubjectEditFreezeTest {

    @Autowired private MockMvc mockMvc;

    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;
    @Autowired private SubjectService subjectService;

    private AdminAuthorizationFixture.Ids ids;

    @BeforeEach
    void seed() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
    }

    /** The payload the edit form submits: every field, whether or not it changed. */
    private SubjectUpdateRequest payloadMirroring(Subject subject) {
        SubjectUpdateRequest request = new SubjectUpdateRequest();
        request.setSubjectName(subject.getSubjectName());
        request.setCourseCode(subject.getCourseCode());
        request.setSemester(subject.getSemester());
        request.setCredits(subject.getCredits());
        request.setSubjectType(subject.getSubjectType().name());
        request.setEligibleDeptIds(List.of());
        return request;
    }

    private Subject csSubject() {
        return subjectRepository.findById(ids.csSubjectId).orElseThrow();
    }

    private void registerAStudentFor(Subject subject) {
        Student student = studentRepository.findById(AdminAuthorizationFixture.CS_ASSIGNED_A).orElseThrow();
        ExamCycle cycle = examCycleRepository.save(new ExamCycle("Freeze Fixture", "2026-01"));
        Registration reg = new Registration();
        reg.setRegId("REG-FREEZE-1");
        reg.setStudent(student);
        reg.setSubjects(List.of(subject));
        reg.setExamCycle(cycle);
        reg.setStatus(RegistrationStatus.SUBMITTED);
        reg.setRegisteredAt(Instant.now());
        reg.setSnapName(student.getName());
        reg.setSnapBranch(student.getBranch());
        reg.setSnapSemester(student.getCurrentSemester());
        reg.setSnapYearOfJoining(student.getYearOfJoining());
        registrationRepository.saveAndFlush(reg);
    }

    // ---- frozen once referenced ----

    @Test
    void refusesARenameOnceAStudentHasRegisteredForTheSubject() {
        Subject subject = csSubject();
        String storedName = subject.getSubjectName();
        registerAStudentFor(subject);

        SubjectUpdateRequest request = payloadMirroring(subject);
        request.setSubjectName("Renamed After The Fact");

        ResponseStatusException thrown = catchThrowableOfType(ResponseStatusException.class,
                () -> subjectService.updateSubject(ids.csSubjectId, request, null));
        assertThat(thrown.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // The message must name the field: the form submits all four, so "something is frozen" does
        // not tell the admin which edit to undo.
        assertThat(thrown.getReason()).contains("subject name");

        // The refusal must leave the row alone. updateSubject mutates a MANAGED entity, so a guard
        // placed after the setters would roll back here but still let a caller inside the same
        // transaction see the new values — assert the stored state, not just the status.
        assertThat(subjectRepository.findById(ids.csSubjectId).orElseThrow().getSubjectName())
                .isEqualTo(storedName);
    }

    @Test
    void namesEveryFrozenFieldThatChanged() {
        Subject subject = csSubject();
        registerAStudentFor(subject);

        SubjectUpdateRequest request = payloadMirroring(subject);
        request.setCourseCode(subject.getCourseCode() + "X");
        request.setCredits(subject.getCredits() + 1);
        request.setSemester(subject.getSemester() == 8 ? 7 : subject.getSemester() + 1);

        ResponseStatusException thrown = catchThrowableOfType(ResponseStatusException.class,
                () -> subjectService.updateSubject(ids.csSubjectId, request, null));
        assertThat(thrown.getReason()).contains("course code").contains("semester").contains("credits");
    }

    // ---- still editable ----

    /** The form submits every field on every save, so a blanket "referenced ⇒ read-only" would
     *  turn the two editable fields into a 409 as well. */
    @Test
    void allowsATypeChangeOnAReferencedSubject() {
        Subject subject = csSubject();
        registerAStudentFor(subject);

        SubjectUpdateRequest request = payloadMirroring(subject);
        request.setSubjectType("ELECTIVE");
        request.setEligibleDeptIds(List.of(ids.csDeptId));

        assertThatCode(() -> subjectService.updateSubject(ids.csSubjectId, request, null))
                .doesNotThrowAnyException();
        assertThat(subjectRepository.findById(ids.csSubjectId).orElseThrow().getSubjectType())
                .isEqualTo(SubjectType.ELECTIVE);
    }

    @Test
    void allowsAResaveThatChangesNothing() {
        Subject subject = csSubject();
        registerAStudentFor(subject);

        assertThatCode(() -> subjectService.updateSubject(ids.csSubjectId, payloadMirroring(subject), null))
                .doesNotThrowAnyException();
    }

    // ---- the UI signal ----

    /**
     * The catalog list carries {@code registered} per row, so the edit form can lock those four
     * fields BEFORE a save instead of discovering the 409 after one. Asserted here rather than in
     * Cypress, which stubs this response and would pass against a server that never sent the flag.
     */
    @Test
    @WithMockUser(username = AdminAuthorizationFixture.ADMIN, roles = "ADMIN")
    void theCatalogListFlagsExactlyTheReferencedSubjects() throws Exception {
        registerAStudentFor(csSubject());

        mockMvc.perform(get("/api/admin/subjects").param("size", "200"))
                .andExpect(status().isOk())
                // Both directions in one request: a flag hardcoded either way fails one of them.
                .andExpect(jsonPath("$.content[?(@.courseCode=='"
                        + AdminAuthorizationFixture.CS_SUBJECT_CODE + "')].registered", contains(true)))
                .andExpect(jsonPath("$.content[?(@.courseCode=='"
                        + AdminAuthorizationFixture.CV_SUBJECT_CODE + "')].registered", contains(false)));
    }

    /** The negative control. Without it every case above would pass on a rule that simply refused
     *  all edits to these fields, registered or not. */
    @Test
    void allowsARenameWhileNoRegistrationReferencesTheSubject() {
        Subject subject = csSubject();
        SubjectUpdateRequest request = payloadMirroring(subject);
        request.setSubjectName("Corrected Before Anyone Registered");

        subjectService.updateSubject(ids.csSubjectId, request, null);

        assertThat(subjectRepository.findById(ids.csSubjectId).orElseThrow().getSubjectName())
                .isEqualTo("Corrected Before Anyone Registered");
    }
}
