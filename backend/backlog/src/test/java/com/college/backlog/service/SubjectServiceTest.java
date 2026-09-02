package com.college.backlog.service;

import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.controller.dto.SubjectUpdateRequest;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.SubjectRepository;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectServiceTest {

    @Mock private SubjectRepository subjectRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private EntityManager entityManager;
    @Mock private RegistrationRepository registrationRepository;
    @InjectMocks private SubjectService service;

    private Subject subject(long id, int year) {
        Subject s = new Subject();
        s.setId(id);
        s.setAcademicYearOffered(year);
        s.setCourseCode("22CSL44");
        s.setSemester(4);
        s.setCredits(4);
        s.setSubjectType(SubjectType.REGULAR);
        return s;
    }

    /** Request that is valid apart from whatever the caller overrides. */
    private SubjectCreateRequest createRequest(int year, String courseCode) {
        SubjectCreateRequest req = new SubjectCreateRequest();
        req.setSubjectName("Data Structures");
        req.setCourseCode(courseCode);
        req.setSemester(4);
        req.setCredits(4);
        req.setAcademicYearOffered(year);
        req.setDeptId(1L);
        return req;
    }

    @Test
    void rejectsAnAcademicYearOfZero() {
        // The regression: @NotNull on a primitive int is a no-op, so an omitted/zero year used to
        // persist. The year is the binding key and nothing else constrains it, so this range check
        // is the only thing standing between a typo and a subject no backlog can ever resolve to.
        assertThatThrownBy(() -> service.createSubject(createRequest(0, "CS44")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("out of range");
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void rejectsAFarFutureAcademicYear() {
        // Upper bound: the DTO's @Min is only a floor, so the range check owns this end.
        assertThatThrownBy(() -> service.createSubject(createRequest(9999, "CS44")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("out of range");
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void blocksDeleteWhenReferencedByRegistrations() {
        when(subjectRepository.findById(10L)).thenReturn(Optional.of(subject(10L, 2022)));
        when(registrationRepository.existsBySubjects_Id(10L)).thenReturn(true);

        assertThatThrownBy(() -> service.deleteSubject(10L, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("referenced");

        verify(subjectRepository, never()).delete(any(Subject.class));
    }

    private SubjectUpdateRequest updateRequest(String courseCode) {
        SubjectUpdateRequest req = new SubjectUpdateRequest();
        req.setSubjectName("Data Structures");
        req.setCourseCode(courseCode);
        req.setSemester(4);
        req.setCredits(4);
        return req;
    }

    /** The mislabel: ANY integrity violation on update used to be reported as "duplicate course
     *  code", explaining someone else's problem with a confident, wrong sentence. */
    @Test
    void anUnrelatedIntegrityViolationIsNotReportedAsADuplicateCourseCode() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L, 2022)));
        when(subjectRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("ERROR: null value in column \"semester\" violates not-null constraint")));

        assertThatThrownBy(() -> service.updateSubject(1L, updateRequest("22CSL44"), null))
                // rethrown, so GlobalExceptionHandler answers a generic 409 and logs it
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theCodeAndYearUniqueViolationStillReportsTheDuplicateMessage() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L, 2022)));
        when(subjectRepository.saveAndFlush(any())).thenThrow(new DataIntegrityViolationException(
                "could not execute statement",
                new RuntimeException("violates unique constraint \"uq_subjects_code_year\"")));

        assertThatThrownBy(() -> service.updateSubject(1L, updateRequest("22CSL44"), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already exists for this academic year");
    }

    /** Unknown type used to fall back to REGULAR, so "ELECTIV" quietly created a REGULAR subject
     *  that then never reached the students the elective was meant for. */
    @Test
    void anUnrecognisedSubjectTypeIsRejectedRatherThanQuietlyBecomingRegular() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L, 2022)));
        SubjectUpdateRequest req = updateRequest("22CSL44");
        req.setSubjectType("ELECTIV");

        assertThatThrownBy(() -> service.updateSubject(1L, req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Unknown subject type");
        verify(subjectRepository, never()).saveAndFlush(any());
    }

    @Test
    void aBlankSubjectTypeStillMeansRegular() {
        Subject existing = subject(1L, 2022);
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(subjectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));
        SubjectUpdateRequest req = updateRequest("22CSL44");
        req.setSubjectType("");

        assertThat(service.updateSubject(1L, req, null).getSubjectType()).isEqualTo(SubjectType.REGULAR);
    }

    /** findAllById just omits ids it can't find, so a stale one silently saved the subject with
     *  narrower eligibility than the admin selected. */
    @Test
    void aStaleEligibleDepartmentIdIsRejectedRatherThanSilentlyDropped() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L, 2022)));
        when(departmentRepository.findAllById(any()))
                .thenReturn(java.util.List.of(new com.college.backlog.model.Department(1L, "CSE", null)));
        SubjectUpdateRequest req = updateRequest("22CSL44");
        req.setSubjectType("ELECTIVE");
        req.setEligibleDeptIds(java.util.List.of(1L, 999L)); // 999 no longer exists

        assertThatThrownBy(() -> service.updateSubject(1L, req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no longer exist");
        verify(subjectRepository, never()).saveAndFlush(any());
    }

    // ---- ELECTIVE must name at least one eligible department ----
    // SubjectType's javadoc states this invariant and nothing enforced it. An ELECTIVE with an
    // empty list is registrable by NOBODY — branchMatches and RegistrationService both anyMatch
    // over that collection — while still listing in the admin catalog. Both React forms already
    // block it, so these cover the server-side half that a direct API call reaches.

    @Test
    void anElectiveCannotBeCreatedWithNoEligibleDepartments() {
        // createSubject resolves the owning department BEFORE the type, so this must be stubbed or
        // the request dies on "Unknown department." and proves nothing about the elective rule.
        when(departmentRepository.findById(1L))
                .thenReturn(Optional.of(new com.college.backlog.model.Department(1L, "CSE", null)));
        SubjectCreateRequest req = createRequest(2022, "22CSL44");
        req.setSubjectType("ELECTIVE");
        req.setEligibleDeptIds(java.util.List.of());

        assertThatThrownBy(() -> service.createSubject(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one eligible department");
        verify(subjectRepository, never()).save(any());
    }

    /** Omitting the field entirely is the same mistake as sending an empty list — and is what a
     *  partial payload from a non-browser client actually looks like. */
    @Test
    void anElectiveCannotBeCreatedWithTheEligibleListOmittedAltogether() {
        when(departmentRepository.findById(1L))
                .thenReturn(Optional.of(new com.college.backlog.model.Department(1L, "CSE", null)));
        SubjectCreateRequest req = createRequest(2022, "22CSL44");
        req.setSubjectType("ELECTIVE");   // eligibleDeptIds left null

        assertThatThrownBy(() -> service.createSubject(req))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one eligible department");
        verify(subjectRepository, never()).save(any());
    }

    /**
     * The sharp edge. On update the old code did not merely fail to ADD eligibility — the else
     * branch overwrote it with an empty list, so an ELECTIVE whose ids were simply omitted from
     * the payload had its real eligibility destroyed and became invisible to every student.
     */
    @Test
    void anUpdateThatOmitsTheEligibleListDoesNotWipeAnExistingElectivesEligibility() {
        Subject existing = subject(1L, 2022);
        existing.setSubjectType(SubjectType.ELECTIVE);
        existing.setEligibleDepartments(new java.util.ArrayList<>(java.util.List.of(
                new com.college.backlog.model.Department(1L, "CSE", null))));
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(existing));

        SubjectUpdateRequest req = updateRequest("22CSL44");
        req.setSubjectType("ELECTIVE");   // eligibleDeptIds left null

        assertThatThrownBy(() -> service.updateSubject(1L, req, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("at least one eligible department");
        verify(subjectRepository, never()).saveAndFlush(any());
        // the refusal must leave the stored eligibility untouched, not half-applied
        assertThat(existing.getEligibleDepartments()).hasSize(1);
    }

    /** The guard must not narrow the REGULAR path: no eligibility is required, and switching an
     *  ELECTIVE back to REGULAR must still clear the list it no longer has any use for. */
    @Test
    void switchingAnElectiveBackToRegularStillClearsItsEligibility() {
        Subject existing = subject(1L, 2022);
        existing.setSubjectType(SubjectType.ELECTIVE);
        existing.setEligibleDepartments(new java.util.ArrayList<>(java.util.List.of(
                new com.college.backlog.model.Department(1L, "CSE", null))));
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(existing));
        when(subjectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SubjectUpdateRequest req = updateRequest("22CSL44");
        req.setSubjectType("REGULAR");   // no eligibleDeptIds, and none needed

        Subject saved = service.updateSubject(1L, req, null);

        assertThat(saved.getSubjectType()).isEqualTo(SubjectType.REGULAR);
        assertThat(saved.getEligibleDepartments()).isEmpty();
    }

    /** A well-formed elective must still go through unchanged. */
    @Test
    void anElectiveNamingItsDepartmentsIsStillAccepted() {
        when(subjectRepository.findById(1L)).thenReturn(Optional.of(subject(1L, 2022)));
        when(departmentRepository.findAllById(any()))
                .thenReturn(java.util.List.of(new com.college.backlog.model.Department(1L, "CSE", null)));
        when(subjectRepository.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        SubjectUpdateRequest req = updateRequest("22CSL44");
        req.setSubjectType("ELECTIVE");
        req.setEligibleDeptIds(java.util.List.of(1L));

        Subject saved = service.updateSubject(1L, req, null);

        assertThat(saved.getSubjectType()).isEqualTo(SubjectType.ELECTIVE);
        assertThat(saved.getEligibleDepartments()).hasSize(1);
    }
}
