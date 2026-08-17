package com.college.backlog.service;

import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ProctorAssignmentRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ProctorScopeServiceTest {

    @Mock private ProctorAssignmentRepository assignmentRepository;
    @InjectMocks private ProctorScopeService service;

    private User user(String username, UserRole role) {
        User u = new User();
        u.setUsername(username);
        u.setRole(role);
        return u;
    }

    // ---- assignedRollNos ----

    @Test
    void nonProctorsAreUnrestrictedAtTheAssignmentLayer() {
        assertThat(service.assignedRollNos(user("hod_cs", UserRole.HOD))).isNull();
        assertThat(service.assignedRollNos(user("admin", UserRole.ADMIN))).isNull();
        assertThat(service.assignedRollNos(null)).isNull();
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void proctorGetsExactlyTheirAssignedSet() {
        when(assignmentRepository.findByProctorUsername("proc1")).thenReturn(List.of(
            new ProctorAssignment("1MS22CS001", "proc1", "hod_cs"),
            new ProctorAssignment("1MS22CS002", "proc1", "proc1")));

        assertThat(service.assignedRollNos(user("proc1", UserRole.PROCTOR)))
            .containsExactlyInAnyOrder("1MS22CS001", "1MS22CS002");
    }

    @Test
    void proctorWithNoAssignmentsGetsAnEmptySetNotNull() {
        when(assignmentRepository.findByProctorUsername("proc1")).thenReturn(List.of());
        assertThat(service.assignedRollNos(user("proc1", UserRole.PROCTOR))).isEmpty();
    }

    // ---- assertSupervises ----

    @Test
    void assertSupervisesIsANoOpForNonProctors() {
        assertThatCode(() -> service.assertSupervises(user("hod_cs", UserRole.HOD), "1MS22CS001"))
            .doesNotThrowAnyException();
        verifyNoInteractions(assignmentRepository);
    }

    @Test
    void proctorPassesOnTheirOwnStudent() {
        when(assignmentRepository.findById("1MS22CS001"))
            .thenReturn(Optional.of(new ProctorAssignment("1MS22CS001", "proc1", "hod_cs")));

        assertThatCode(() -> service.assertSupervises(user("proc1", UserRole.PROCTOR), "1MS22CS001"))
            .doesNotThrowAnyException();
    }

    @Test
    void proctorIsForbiddenOnAnUnassignedStudent() {
        when(assignmentRepository.findById("1MS22CS009")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertSupervises(user("proc1", UserRole.PROCTOR), "1MS22CS009"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void proctorIsForbiddenOnAnotherProctorsStudent() {
        when(assignmentRepository.findById("1MS22CS001"))
            .thenReturn(Optional.of(new ProctorAssignment("1MS22CS001", "proc2", "hod_cs")));

        assertThatThrownBy(() -> service.assertSupervises(user("proc1", UserRole.PROCTOR), "1MS22CS001"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }

    // ---- rejectProctor ----

    @Test
    void rejectProctorOnlyBlocksProctors() {
        assertThatCode(() -> service.rejectProctor(user("hod_cs", UserRole.HOD), "no"))
            .doesNotThrowAnyException();

        assertThatThrownBy(() -> service.rejectProctor(user("proc1", UserRole.PROCTOR), "no"))
            .isInstanceOfSatisfying(ResponseStatusException.class,
                e -> assertThat(e.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN));
    }
}
