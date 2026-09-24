package com.college.backlog.repository;

import com.college.backlog.model.RecipientOutcome;
import com.college.backlog.model.ReminderRecipient;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Set;

public interface ReminderRecipientRepository extends JpaRepository<ReminderRecipient, Long> {

    @Query("select r.rollNo from ReminderRecipient r where r.reminderId = :reminderId")
    Set<String> findHandledRollNos(@Param("reminderId") Long reminderId);

    /** Rows of [reminderId, outcome, count] for the list page — one query for every reminder. */
    @Query("select r.reminderId, r.outcome, count(r) from ReminderRecipient r "
            + "where r.reminderId in :ids group by r.reminderId, r.outcome")
    List<Object[]> countByReminderAndOutcome(@Param("ids") Collection<Long> ids);

    long countByOutcomeAndProcessedAtAfter(RecipientOutcome outcome, Instant since);

    List<ReminderRecipient> findByReminderIdAndOutcomeOrderByRollNo(Long reminderId, RecipientOutcome outcome);
}
