package com.college.backlog.service;

import com.college.backlog.model.*;
import com.college.backlog.repository.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The "one live pending registration per exam cycle" rule (MAX_PENDING_PER_CYCLE = 1) and its DB
 * race backstop. Everything upstream of the limit check is stubbed to a valid single-subject
 * submission, so only the limit path is exercised.
 */
class RegistrationServiceTest {

    @Mock private RegistrationRepository registrationRepository;
    @Mock private StudentRepository studentRepository;
    @Mock private SubjectRepository subjectRepository;
    @Mock private ExamCycleRepository examCycleRepository;
    @Mock private RegistrationEventRepository registrationEventRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private EligibilityService eligibilityService;
    @Mock private StudentSemesterTermRepository studentSemesterTermRepository;

    @InjectMocks private RegistrationService service;

    private static final String ROLL = "1MS22CS001";
    private ExamCycle cycle;
    private Subject subject;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        cycle = new ExamCycle("Cycle A", "May 2026");
        cycle.setId(10L);
        cycle.setActive(true);
        when(examCycleRepository.findByActiveTrue()).thenReturn(Optional.of(cycle));

        Student student = new Student();
        student.setRollNo(ROLL);
        student.setName("Alice");
        student.setPhone("9999999999");
        student.setCurrentSemester(4);
        student.setEntrySemester(1);
        when(studentRepository.findByRollNo(ROLL)).thenReturn(Optional.of(student));

        Department cs = new Department();
        cs.setId(1L);
        cs.setDeptName("Computer Science");
        cs.setCode("CS");
        when(departmentRepository.findByCodeIgnoreCase("CS")).thenReturn(Optional.of(cs));

        when(eligibilityService.eligibleSemesters(anyInt(), anyInt())).thenReturn(Set.of(4));

        subject = new Subject();
        subject.setId(100L);
        subject.setSubjectName("Data Structures");
        subject.setCourseCode("22CSL44");
        subject.setSemester(4);
        subject.setCredits(4);
        subject.setAcademicYearOffered(2022);
        subject.setDepartment(cs);
        subject.setSubjectType(SubjectType.REGULAR);
        when(subjectRepository.findAllById(List.of(100L))).thenReturn(List.of(subject));

        when(studentSemesterTermRepository.findByRollNo(ROLL))
                .thenReturn(List.of(new StudentSemesterTerm(ROLL, 4, 2022)));
    }

    @Test
    void secondPendingInSameCycleIsRejectedWith409() {
        // an existing SUBMITTED row in this cycle -> at the limit of 1
        when(registrationRepository.countByStudent_RollNoAndExamCycle_IdAndStatus(
                ROLL, 10L, RegistrationStatus.SUBMITTED))
                .thenReturn(1L);

        ResponseStatusException ex = catchThrowableOfType(
                ResponseStatusException.class, () -> service.register(ROLL, List.of(100L)));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        // never attempts the insert once the limit is hit
        verify(registrationRepository, never()).saveAndFlush(any());
    }

    @Test
    void actionedRegistrationsInTheCycleDoNotCountTowardTheLimit() {
        // VERIFIED/REJECTED rows but no SUBMITTED: the count is status-filtered, returns 0, allowed
        when(registrationRepository.countByStudent_RollNoAndExamCycle_IdAndStatus(
                ROLL, 10L, RegistrationStatus.SUBMITTED))
                .thenReturn(0L);
        when(registrationRepository.saveAndFlush(any()))
                .thenAnswer(inv -> inv.getArgument(0));

        Registration saved = service.register(ROLL, List.of(100L));

        assertThat(saved.getStatus()).isEqualTo(RegistrationStatus.SUBMITTED);
        verify(registrationRepository).saveAndFlush(any());
    }

    @Test
    void concurrentInsertRaceIsMappedTo409ByTheUniqueIndexBackstop() {
        // count check passes (no SUBMITTED yet) but the partial unique index rejects the
        // concurrent insert -> 409, not 500
        when(registrationRepository.countByStudent_RollNoAndExamCycle_IdAndStatus(
                ROLL, 10L, RegistrationStatus.SUBMITTED))
                .thenReturn(0L);
        when(registrationRepository.saveAndFlush(any()))
                .thenThrow(new DataIntegrityViolationException("duplicate key uq_pending_reg_per_cycle"));

        assertThatThrownBy(() -> service.register(ROLL, List.of(100L)))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.CONFLICT));

        verify(registrationEventRepository, never()).save(any());
    }

    // ---- applyVerification (verify/reject + audit event in one transaction) ----

    private Registration pendingRegistration(String regId) {
        Registration reg = new Registration();
        reg.setRegId(regId);
        reg.setStatus(RegistrationStatus.SUBMITTED);
        when(registrationRepository.findByRegId(regId)).thenReturn(Optional.of(reg));
        return reg;
    }

    @Test
    void applyVerificationFlipsStatusAndWritesTheAuditEvent() {
        Registration reg = pendingRegistration("reg-1");
        when(registrationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Registration result = service.applyVerification(
                "reg-1", RegistrationStatus.VERIFIED, "hodcs", ActorRole.HOD);

        assertThat(result.getStatus()).isEqualTo(RegistrationStatus.VERIFIED);
        assertThat(result.getVerifiedBy()).isEqualTo("hodcs");
        ArgumentCaptor<RegistrationEvent> event = ArgumentCaptor.forClass(RegistrationEvent.class);
        verify(registrationEventRepository).save(event.capture());
        assertThat(event.getValue().getRegId()).isEqualTo("reg-1");
        assertThat(event.getValue().getAction()).isEqualTo(EventAction.VERIFIED);
        assertThat(event.getValue().getActor()).isEqualTo("hodcs");
        assertThat(event.getValue().getActorRole()).isEqualTo(ActorRole.HOD);
        assertThat(reg).isSameAs(result);
    }

    @Test
    void applyVerificationCannotReVerifyOrUnRejectARejectedRegistrationWith409() {
        // REJECTED is terminal: no un-reject back to VERIFIED
        Registration reg = pendingRegistration("reg-2");
        reg.setStatus(RegistrationStatus.REJECTED);

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
                () -> service.applyVerification("reg-2", RegistrationStatus.VERIFIED, "admin", ActorRole.ADMIN));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(registrationRepository, never()).saveAndFlush(any());
        verify(registrationEventRepository, never()).save(any());
    }

    @Test
    void applyVerificationCanRejectAnAlreadyVerifiedRegistrationAndWritesTheAuditEvent() {
        // one-way tightening: a completed verification can still be overridden to REJECTED
        Registration reg = pendingRegistration("reg-2b");
        reg.setStatus(RegistrationStatus.VERIFIED);
        when(registrationRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        Registration result = service.applyVerification(
                "reg-2b", RegistrationStatus.REJECTED, "hodcs", ActorRole.HOD);

        assertThat(result.getStatus()).isEqualTo(RegistrationStatus.REJECTED);
        ArgumentCaptor<RegistrationEvent> event = ArgumentCaptor.forClass(RegistrationEvent.class);
        verify(registrationEventRepository).save(event.capture());
        assertThat(event.getValue().getAction()).isEqualTo(EventAction.REJECTED);
        assertThat(event.getValue().getActor()).isEqualTo("hodcs");
    }

    @Test
    void applyVerificationCannotReRejectAnAlreadyRejectedRegistrationWith409() {
        Registration reg = pendingRegistration("reg-2c");
        reg.setStatus(RegistrationStatus.REJECTED);

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
                () -> service.applyVerification("reg-2c", RegistrationStatus.REJECTED, "admin", ActorRole.ADMIN));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(registrationRepository, never()).saveAndFlush(any());
        verify(registrationEventRepository, never()).save(any());
    }

    @Test
    void applyVerificationMapsAConcurrentActionTo409AndWritesNoEvent() {
        pendingRegistration("reg-3");
        when(registrationRepository.saveAndFlush(any()))
                .thenThrow(new OptimisticLockingFailureException("row version changed"));

        ResponseStatusException ex = catchThrowableOfType(ResponseStatusException.class,
                () -> service.applyVerification("reg-3", RegistrationStatus.VERIFIED, "admin", ActorRole.ADMIN));

        assertThat(ex).isNotNull();
        assertThat(ex.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        verify(registrationEventRepository, never()).save(any());
    }

    @Test
    void applyVerificationRefusesANonTerminalAction() {
        assertThatThrownBy(() -> service.applyVerification(
                "reg-4", RegistrationStatus.SUBMITTED, "admin", ActorRole.ADMIN))
                .isInstanceOf(IllegalArgumentException.class);
        verify(registrationRepository, never()).saveAndFlush(any());
        verify(registrationEventRepository, never()).save(any());
    }
}
