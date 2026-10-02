package com.college.backlog.repository;

import com.college.backlog.model.Reminder;
import com.college.backlog.model.ReminderStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ReminderRepository extends JpaRepository<Reminder, Long> {

    List<Reminder> findAllByOrderBySendAtDesc();

    /** Due = unfinished and past its time; oldest first so an earlier reminder is never starved. */
    List<Reminder> findByStatusInAndSendAtLessThanEqualOrderBySendAtAsc(
            Collection<ReminderStatus> statuses, Instant now);

    /** The earliest unfinished send_at — what the runner's in-memory "nothing due before" is set to. */
    @Query("select min(r.sendAt) from Reminder r where r.status in :statuses")
    Optional<Instant> findEarliestSendAt(@Param("statuses") Collection<ReminderStatus> statuses);
}
