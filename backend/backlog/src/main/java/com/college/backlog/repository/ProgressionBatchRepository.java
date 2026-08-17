package com.college.backlog.repository;

import com.college.backlog.model.ProgressionBatch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface ProgressionBatchRepository extends JpaRepository<ProgressionBatch, Long> {

    Page<ProgressionBatch> findAllByOrderByRunAtDesc(Pageable pageable);
}
