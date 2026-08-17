package com.college.backlog.repository;

import com.college.backlog.model.ProctorAssignment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface ProctorAssignmentRepository extends JpaRepository<ProctorAssignment, String> {

    List<ProctorAssignment> findByProctorUsername(String proctorUsername);

    // batch lookup for the claim picker: which students on a page are already supervised, in
    // one query rather than N
    List<ProctorAssignment> findByRollNoIn(Collection<String> rollNos);

    boolean existsByProctorUsername(String proctorUsername);
}
