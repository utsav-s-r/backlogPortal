package com.college.backlog.repository;

import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RegistrationRepository extends JpaRepository<Registration, Long>, JpaSpecificationExecutor<Registration> {
    // Fetch-joins what every caller touches: the dept/proctor scope checks walk `subjects` (and
    // each subject's eligible departments) before the response is built, and the PDF path needs
    // the same. Joining the collection is safe here — single-row lookup, no pagination to break.
    //
    // type = LOAD is load-bearing, and applies to all three unpaginated graphs below. The default
    // (FETCH) treats every attribute NOT named here as LAZY, overriding the mapping — it silently
    // demoted the EAGER Subject.eligibleDepartments, so a dept-scope DENIAL threw
    // LazyInitializationException (500) instead of 403 with open-in-view off. LOAD keeps unlisted
    // attributes at their mapped type, so that collection stays EAGER on its own @BatchSize(50).
    // Naming it here instead would fetch a second bag — MultipleBagFetchException.
    @EntityGraph(attributePaths = {"student", "subjects", "examCycle"}, type = EntityGraph.EntityGraphType.LOAD)
    Optional<Registration> findByRegId(String regId);

    @EntityGraph(attributePaths = {"student", "subjects", "examCycle"}, type = EntityGraph.EntityGraphType.LOAD)
    List<Registration> findByStudent_RollNo(String rollNo);

    // Pending-limit check: one indexed COUNT (served exactly by the partial unique index
    // uq_pending_reg_per_cycle when status=SUBMITTED), not rows hydrated to count in Java.
    long countByStudent_RollNoAndExamCycle_IdAndStatus(String rollNo, Long examCycleId, RegistrationStatus status);

    // Delete guard: blocks deleting a subject students have already registered for.
    boolean existsBySubjects_Id(Long subjectId);

    // Delete guard: blocks deleting a student referenced by immutable registration history.
    boolean existsByStudent_RollNo(String rollNo);

    // Fetch-joins what the PDF export touches per row; safe because this overload is unpaginated.
    @Override
    @EntityGraph(attributePaths = {"student", "subjects", "examCycle"}, type = EntityGraph.EntityGraphType.LOAD)
    List<Registration> findAll(Specification<Registration> spec, Sort sort);

    // Paginated admin list: ManyToOne relations only — fetching the `subjects` collection here
    // would make Hibernate paginate in memory. `subjects` loads per row via @BatchSize on mapping.
    @Override
    @EntityGraph(attributePaths = {"student", "examCycle"})
    Page<Registration> findAll(Specification<Registration> spec, Pageable pageable);
}