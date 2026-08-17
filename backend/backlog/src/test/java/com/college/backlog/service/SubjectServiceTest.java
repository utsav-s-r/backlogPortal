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
    void rejectsAnAcademicYearOfZeroEvenWhenTheCourseCodePrefixAgrees() {
        // The regression: @NotNull on a primitive int is a no-op, so an omitted/zero year used to
        // persist. "00CS44" satisfies the prefix invariant (floorMod(0,100) == 0), so the prefix
        // check cannot catch it — the year must be range-checked in its own right.
        assertThatThrownBy(() -> service.createSubject(createRequest(0, "00CS44")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("out of range");
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void rejectsAFarFutureAcademicYearEvenWhenTheCourseCodePrefixAgrees() {
        // Upper bound: "99CS44" matches year 9999, so only the range check rejects this.
        assertThatThrownBy(() -> service.createSubject(createRequest(9999, "99CS44")))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("out of range");
        verify(subjectRepository, never()).save(any());
    }

    @Test
    void rejectsCourseCodeWhosePrefixDoesNotMatchTheYearOnCreate() {
        SubjectCreateRequest req = new SubjectCreateRequest();
        req.setSubjectName("Data Structures");
        req.setCourseCode("23CSL44");      // prefix 23 ...
        req.setSemester(4);
        req.setCredits(4);
        req.setAcademicYearOffered(2022);  // ... but the year is 2022
        req.setDeptId(1L);

        // validated before any repository interaction, so no stubbing is needed
        assertThatThrownBy(() -> service.createSubject(req))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("two digits");
    }

    @Test
    void rejectsCourseCodeWhosePrefixDoesNotMatchTheYearOnUpdate() {
        when(subjectRepository.findById(10L)).thenReturn(Optional.of(subject(10L, 2022)));

        SubjectUpdateRequest req = new SubjectUpdateRequest();
        req.setSubjectName("Data Structures");
        req.setCourseCode("23CSL44"); // prefix 23 against a fixed year of 2022
        req.setSemester(4);
        req.setCredits(3);

        assertThatThrownBy(() -> service.updateSubject(10L, req, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("two digits");
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
}
