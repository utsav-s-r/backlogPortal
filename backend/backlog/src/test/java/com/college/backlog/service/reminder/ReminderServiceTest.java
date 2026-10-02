package com.college.backlog.service.reminder;

import com.college.backlog.controller.dto.ReminderRequest;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Reminder;
import com.college.backlog.model.User;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ReminderRepository;
import com.college.backlog.service.AdminAuditService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The runner must be invalidated only AFTER the create commits: before it, a cron ping re-reads
 * the database without the new row and caches "nothing due", so the reminder never sends.
 */
@ExtendWith(MockitoExtension.class)
class ReminderServiceTest {

    @Mock private ReminderRepository reminderRepository;
    @Mock private ExamCycleRepository examCycleRepository;
    @Mock private DepartmentRepository departmentRepository;
    @Mock private ReminderRunner runner;
    @Mock private AdminAuditService auditService;
    @InjectMocks private ReminderService service;

    @AfterEach
    void clearSync() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void createInvalidatesTheRunnerOnlyAfterCommit() {
        ExamCycle cycle = new ExamCycle("June 2026", "2026-06");
        ReflectionTestUtils.setField(cycle, "id", 3L);
        when(examCycleRepository.findById(3L)).thenReturn(Optional.of(cycle));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(i -> i.getArgument(0));
        User admin = new User();
        admin.setUsername("admin");

        ReminderRequest req = new ReminderRequest();
        req.setExamCycleId(3L);
        req.setSubject("s");
        req.setMessage("m");
        req.setSendAt(LocalDateTime.now(ZoneId.of("Asia/Kolkata")).plusDays(1).withNano(0).toString().substring(0, 16));

        TransactionSynchronizationManager.initSynchronization();
        service.create(req, admin);

        verify(runner, never()).invalidate();
        for (TransactionSynchronization s : TransactionSynchronizationManager.getSynchronizations()) {
            s.afterCommit();
        }
        verify(runner).invalidate();
    }
}
