package com.college.backlog.repository;

import com.college.backlog.model.ProgressionBatchStudent;
import com.college.backlog.model.ProgressionOutcome;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ProgressionBatchStudentRepository extends JpaRepository<ProgressionBatchStudent, Long> {

    Page<ProgressionBatchStudent> findByBatchIdOrderByRollNo(Long batchId, Pageable pageable);

    // header counts are taken from the rows actually written, not recomputed from the request —
    // an excluded USN outside the run's filter never becomes a row, so the request list would
    // over-count it
    int countByBatchIdAndOutcome(Long batchId, ProgressionOutcome outcome);

    // the non-promoted outcomes are the small, human-checkable ones — returned in full on the
    // batch-detail screen while the promoted set stays a count
    List<ProgressionBatchStudent> findByBatchIdAndOutcomeNotOrderByRollNo(
        Long batchId, ProgressionOutcome outcome);

}
