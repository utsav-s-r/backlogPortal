package com.college.backlog.service.reminder;

import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.RecipientOutcome;
import com.college.backlog.model.Reminder;
import com.college.backlog.model.ReminderRecipient;
import com.college.backlog.model.ReminderStatus;
import com.college.backlog.repository.ReminderRecipientRepository;
import com.college.backlog.repository.ReminderRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderRunnerTest {

    private static final Instant NOW = Instant.parse("2026-10-01T04:00:00Z");

    @Mock private ReminderRepository reminderRepository;
    @Mock private ReminderRecipientRepository recipientRepository;
    @Mock private ReminderAudience audience;
    @Mock private ReminderMailer mailer;
    @InjectMocks private ReminderRunner runner;

    private Reminder reminder;
    /** Per-address outcome: absent = accepted. */
    private final Map<String, MailFailure> failures = new java.util.HashMap<>();
    private final List<String> attempted = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        ReflectionTestUtils.setField(runner, "clock", Clock.fixed(NOW, ZoneOffset.UTC));
        ReflectionTestUtils.setField(runner, "dailyCap", 280);

        ExamCycle cycle = new ExamCycle("June 2026", "2026-06");
        ReflectionTestUtils.setField(cycle, "id", 3L);
        reminder = new Reminder(cycle, null, "Subj", "Msg", NOW.minusSeconds(60), "admin", NOW.minusSeconds(3600));
        ReflectionTestUtils.setField(reminder, "id", 7L);

        lenient().when(mailer.isConfigured()).thenReturn(true);
        lenient().when(reminderRepository.findByStatusInAndSendAtLessThanEqualOrderBySendAtAsc(any(), eq(NOW)))
                .thenReturn(List.of(reminder));
        lenient().when(reminderRepository.save(any(Reminder.class))).thenAnswer(i -> i.getArgument(0));
        lenient().when(reminderRepository.findById(7L)).thenAnswer(i -> Optional.of(reminder));
        lenient().when(reminderRepository.findEarliestSendAt(any())).thenReturn(Optional.empty());
        lenient().when(recipientRepository.findHandledRollNos(7L)).thenReturn(Set.of());
        lenient().when(recipientRepository.countByOutcomeAndProcessedAtAfter(eq(RecipientOutcome.SENT), any()))
                .thenReturn(0L);
        // one stub that branches inside — STRICT_STUBS + several argThat()s is the trap noted in CLAUDE.local.md
        lenient().doAnswer(i -> {
            String to = i.getArgument(0);
            attempted.add(to);
            MailFailure f = failures.get(to);
            if (f != null) throw f;
            return null;
        }).when(mailer).send(anyString(), anyString(), any());
    }

    private void audienceOf(String... rollNos) {
        List<ReminderAudience.Recipient> list = new ArrayList<>();
        for (String r : rollNos) {
            list.add(new ReminderAudience.Recipient(r, "Name " + r, r.toLowerCase() + "@msrit.edu",
                    List.of("Subject (CODE)")));
        }
        when(audience.resolve(3L, null)).thenReturn(list);
    }

    private static String mail(String rollNo) {
        return rollNo.toLowerCase() + "@msrit.edu";
    }

    private List<ReminderRecipient> logged() {
        ArgumentCaptor<ReminderRecipient> c = ArgumentCaptor.forClass(ReminderRecipient.class);
        verify(recipientRepository, org.mockito.Mockito.atLeast(0)).save(c.capture());
        return c.getAllValues();
    }

    private static MailFailure rejected() {
        return new MailFailure(MailFailure.Kind.REJECTED, "Brevo 400: bad address");
    }

    @Test
    void unconfiguredMailTouchesNoDatabase() {
        when(mailer.isConfigured()).thenReturn(false);

        runner.runDue();

        verifyNoInteractions(reminderRepository, recipientRepository, audience);
    }

    @Test
    void sendsOnlyToStudentsNotAlreadyLoggedThenMarksSent() {
        audienceOf("1MS24CS001", "1MS24CS002", "1MS24CS003");
        when(recipientRepository.findHandledRollNos(7L)).thenReturn(Set.of("1MS24CS002"));

        runner.runDue();

        assertThat(attempted).containsExactly(mail("1MS24CS001"), mail("1MS24CS003"));
        assertThat(logged()).extracting(ReminderRecipient::getRollNo, ReminderRecipient::getOutcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1MS24CS001", RecipientOutcome.SENT),
                        org.assertj.core.groups.Tuple.tuple("1MS24CS003", RecipientOutcome.SENT));
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(reminder.getCompletedAt()).isEqualTo(NOW);
    }

    @Test
    void anOutageStopsTheRunWithoutBlamingAnyone() {
        audienceOf("1MS24CS001", "1MS24CS002");
        failures.put(mail("1MS24CS001"), new MailFailure(MailFailure.Kind.UNAVAILABLE, "Brevo 401: key not found"));

        runner.runDue();

        assertThat(attempted).containsExactly(mail("1MS24CS001"));
        assertThat(logged()).isEmpty();
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENDING);
        assertThat(reminder.getLastError()).contains("key not found");
    }

    @Test
    void aRejectionAfterASuccessFailsThatStudentAndCarriesOn() {
        audienceOf("1MS24CS001", "1MS24CS002", "1MS24CS003");
        failures.put(mail("1MS24CS002"), rejected());

        runner.runDue();

        assertThat(logged()).extracting(ReminderRecipient::getRollNo, ReminderRecipient::getOutcome)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("1MS24CS001", RecipientOutcome.SENT),
                        org.assertj.core.groups.Tuple.tuple("1MS24CS002", RecipientOutcome.FAILED),
                        org.assertj.core.groups.Tuple.tuple("1MS24CS003", RecipientOutcome.SENT));
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENT);
    }

    /** A bad address at the head of the list must not stall the reminder forever. */
    @Test
    void anEarlyRejectionIsFailedOnceALaterSendProvesTheSetup() {
        audienceOf("1MS24CS001", "1MS24CS002");
        failures.put(mail("1MS24CS001"), rejected());

        runner.runDue();

        assertThat(logged()).extracting(ReminderRecipient::getRollNo, ReminderRecipient::getOutcome)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("1MS24CS001", RecipientOutcome.FAILED),
                        org.assertj.core.groups.Tuple.tuple("1MS24CS002", RecipientOutcome.SENT));
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENT);
    }

    /** Three refusals and nothing accepted: the setup, not the students. Nobody is marked FAILED. */
    @Test
    void threeRejectionsWithNoSuccessStopAndBlameNobody() {
        audienceOf("1MS24CS001", "1MS24CS002", "1MS24CS003", "1MS24CS004");
        for (String r : List.of("1MS24CS001", "1MS24CS002", "1MS24CS003", "1MS24CS004")) {
            failures.put(mail(r), rejected());
        }

        runner.runDue();

        assertThat(attempted).hasSize(3);
        assertThat(logged()).isEmpty();
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENDING);
        assertThat(reminder.getLastError()).contains("bad address");
    }

    @Test
    void theDailyCapStopsTheRunAndLeavesTheRestForLater() {
        ReflectionTestUtils.setField(runner, "dailyCap", 10);
        when(recipientRepository.countByOutcomeAndProcessedAtAfter(eq(RecipientOutcome.SENT), any())).thenReturn(9L);
        audienceOf("1MS24CS001", "1MS24CS002");

        runner.runDue();

        assertThat(attempted).containsExactly(mail("1MS24CS001"));
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENDING);
        assertThat(reminder.getLastError()).contains("Daily email limit");
    }

    @Test
    void aCancelBetweenSendsStopsTheRest() throws Exception {
        audienceOf("1MS24CS001", "1MS24CS002");
        doAnswer(i -> {
            attempted.add(i.getArgument(0));
            reminder.markCancelled("admin", NOW);
            return null;
        }).when(mailer).send(eq(mail("1MS24CS001")), anyString(), any());

        runner.runDue();

        assertThat(attempted).containsExactly(mail("1MS24CS001"));
        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.CANCELLED);
    }

    /** A bug inside the send (not a provider answer) must surface on the page, not escape. */
    @Test
    void anUnexpectedErrorStopsWithAVisibleErrorInsteadOfEscaping() throws Exception {
        audienceOf("1MS24CS001", "1MS24CS002");
        doAnswer(i -> { throw new NullPointerException("boom"); })
                .when(mailer).send(eq(mail("1MS24CS001")), anyString(), any());

        runner.runDue();

        assertThat(reminder.getStatus()).isEqualTo(ReminderStatus.SENDING);
        assertThat(reminder.getLastError()).contains("1MS24CS001").contains("boom");
        assertThat(logged()).isEmpty();
    }

    /** Cancelled between the due query and SCHEDULED -> SENDING: skip it, keep the run going. */
    @Test
    void aCancelBeforeStartSkipsThatReminderOnly() {
        ExamCycle cycle = reminder.getExamCycle();
        Reminder second = new Reminder(cycle, null, "Other", "Msg", NOW.minusSeconds(30), "admin", NOW);
        ReflectionTestUtils.setField(second, "id", 8L);
        when(reminderRepository.findByStatusInAndSendAtLessThanEqualOrderBySendAtAsc(any(), eq(NOW)))
                .thenReturn(List.of(reminder, second));
        when(reminderRepository.save(any(Reminder.class))).thenAnswer(i -> {
            Reminder r = i.getArgument(0);
            if (r == reminder && r.getStatus() == ReminderStatus.SENDING && r.getCompletedAt() == null
                    && attempted.isEmpty()) {
                throw new org.springframework.orm.ObjectOptimisticLockingFailureException(Reminder.class, 7L);
            }
            return r;
        });
        when(reminderRepository.findById(8L)).thenReturn(Optional.of(second));
        when(recipientRepository.findHandledRollNos(8L)).thenReturn(Set.of());
        audienceOf("1MS24CS001");

        runner.runDue();

        assertThat(second.getStatus()).isEqualTo(ReminderStatus.SENT);
        assertThat(attempted).containsExactly(mail("1MS24CS001"));
    }

    /** An invalidate landing after the run's final query must not be overwritten by it. */
    @Test
    void anInvalidateDuringTheRunIsNotOverwrittenByItsResult() {
        when(reminderRepository.findByStatusInAndSendAtLessThanEqualOrderBySendAtAsc(any(), eq(NOW)))
                .thenReturn(List.of());
        when(reminderRepository.findEarliestSendAt(any())).thenAnswer(i -> {
            runner.invalidate(); // a reminder commits while this run is finishing
            return Optional.empty();
        });

        runner.runDue();

        assertThat(ReflectionTestUtils.getField(runner, "quietUntil")).isNull();
    }

    @Test
    void nothingDueIsAnsweredFromMemoryUntilInvalidated() throws Exception {
        when(reminderRepository.findByStatusInAndSendAtLessThanEqualOrderBySendAtAsc(any(), eq(NOW)))
                .thenReturn(List.of());
        when(reminderRepository.findEarliestSendAt(any())).thenReturn(Optional.of(NOW.plusSeconds(3600)));

        runner.runDue();

        assertThat(runner.trigger()).isEqualTo(ReminderRunner.TriggerResult.NOTHING_DUE);
        verify(reminderRepository, times(1)).findEarliestSendAt(any());

        runner.invalidate();
        assertThat(runner.trigger()).isNotEqualTo(ReminderRunner.TriggerResult.NOTHING_DUE);
        verify(mailer, never()).send(anyString(), anyString(), any());
    }
}
