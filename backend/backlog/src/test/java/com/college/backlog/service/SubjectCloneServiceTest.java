package com.college.backlog.service;

import com.college.backlog.controller.dto.SubjectCloneApplyRequest;
import com.college.backlog.controller.dto.SubjectCloneResult;
import com.college.backlog.controller.dto.SubjectClonePreviewResponse;
import com.college.backlog.controller.dto.SubjectCreateRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;
import com.college.backlog.repository.SubjectRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SubjectCloneServiceTest {

    @Mock private SubjectRepository subjectRepository;
    @Mock private SubjectService subjectService;
    @InjectMocks private SubjectCloneService service;

    private Subject subject(String name, String code, int sem, int credits) {
        Department d = new Department();
        d.setId(1L);
        Subject s = new Subject();
        s.setSubjectName(name);
        s.setCourseCode(code);
        s.setSemester(sem);
        s.setCredits(credits);
        s.setSubjectType(SubjectType.REGULAR);
        s.setDepartment(d);
        return s;
    }

    @Test
    void previewBumpsAndFlagsExisting() {
        Subject a = subject("Data Structures", "22CSL44", 4, 4);
        Subject b = subject("Operating Systems", "22CSL45", 4, 3);
        when(subjectRepository
                .findByDepartment_IdAndAcademicYearOfferedAndSemesterInOrderBySemesterAscSubjectNameAsc(eq(1L), eq(2022), any()))
            .thenReturn(List.of(a, b));
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("23CSL44", 2023)).thenReturn(false);
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("23CSL45", 2023)).thenReturn(true);

        SubjectClonePreviewResponse res = service.preview(1L, 2022, 2023, null);

        assertThat(res.getRows()).hasSize(2);
        assertThat(res.getRows().get(0).getCourseCode()).isEqualTo("23CSL44");
        assertThat(res.getRows().get(0).getStatus()).isEqualTo("WOULD_CREATE");
        assertThat(res.getRows().get(1).getStatus()).isEqualTo("WOULD_SKIP");
    }

    @Test
    void applyCreatesNewSkipsExistingAndForcesTargetYear() {
        SubjectCloneApplyRequest.Row newRow = new SubjectCloneApplyRequest.Row();
        newRow.setSubjectName("Data Structures");
        newRow.setCourseCode("23CSL44");
        newRow.setSemester(4);
        newRow.setCredits(4);
        SubjectCloneApplyRequest.Row existingRow = new SubjectCloneApplyRequest.Row();
        existingRow.setSubjectName("Operating Systems");
        existingRow.setCourseCode("23CSL45");
        existingRow.setSemester(4);
        existingRow.setCredits(3);

        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("23CSL44", 2023)).thenReturn(false);
        when(subjectRepository.existsByCourseCodeAndAcademicYearOffered("23CSL45", 2023)).thenReturn(true);

        SubjectCloneResult res = service.apply(1L, 2023, List.of(newRow, existingRow));

        assertThat(res.getCreated()).isEqualTo(1);
        assertThat(res.getSkipped()).isEqualTo(1);
        assertThat(res.getErrors()).isZero();

        ArgumentCaptor<SubjectCreateRequest> captor = ArgumentCaptor.forClass(SubjectCreateRequest.class);
        verify(subjectService, times(1)).createSubject(captor.capture());
        assertThat(captor.getValue().getCourseCode()).isEqualTo("23CSL44");
        assertThat(captor.getValue().getAcademicYearOffered()).isEqualTo(2023);
        assertThat(captor.getValue().getDeptId()).isEqualTo(1L);
    }
}
