package com.college.backlog.repository;

import com.college.backlog.model.StudentSemesterTerm;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StudentSemesterTermRepository extends JpaRepository<StudentSemesterTerm, Long> {
    List<StudentSemesterTerm> findByRollNo(String rollNo);

    Optional<StudentSemesterTerm> findByRollNoAndSemester(String rollNo, int semester);
}
