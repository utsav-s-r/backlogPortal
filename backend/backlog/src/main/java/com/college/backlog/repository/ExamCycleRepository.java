package com.college.backlog.repository;

import com.college.backlog.model.ExamCycle;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ExamCycleRepository extends JpaRepository<ExamCycle, Long> {
    Optional<ExamCycle> findByActiveTrue();
    List<ExamCycle> findAllByOrderByCreatedAtDesc();

    // Single bulk UPDATE, so closing the open cycle can't lose a write to a concurrent
    // activation — no read-modify-write over the table.
    @Modifying
    @Query("UPDATE ExamCycle e SET e.active = false WHERE e.active = true")
    int deactivateAll();

    // Activation's close step. Excludes the target on purpose: a bulk UPDATE bypasses the
    // persistence context, so touching a row the caller already loaded leaves that entity stale —
    // re-activating the open cycle set it false in the DB while setActive(true) flushed nothing.
    @Modifying
    @Query("UPDATE ExamCycle e SET e.active = false WHERE e.active = true AND e.id <> :keepId")
    int deactivateAllExcept(@Param("keepId") Long keepId);
}
