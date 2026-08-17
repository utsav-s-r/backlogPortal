package com.college.backlog.repository;

import com.college.backlog.model.Subject;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.stereotype.Repository;
import java.util.Collection;
import java.util.List;

@Repository
public interface SubjectRepository extends JpaRepository<Subject, Long>, JpaSpecificationExecutor<Subject> {

    // Subjects resolve by the academic year the student actually studied the semester (from
    // their progression), never a client-supplied year. Param order is (semester, year) to match
    // the rest of the codebase — both are int, so a swap here compiles and returns empty.
    // Served by ix_subjects_year_semester (V5, named there under this method's former name
    // findByAcademicYearOfferedAndSemester); both columns are equality-matched, so the index
    // works either way round.
    List<Subject> findBySemesterAndAcademicYearOffered(int semester, int academicYearOffered);

    // Clone source: a department's offerings for one academic year, optionally narrowed to
    // specific semesters. Ordered for a stable preview grid.
    List<Subject> findByDepartment_IdAndAcademicYearOfferedAndSemesterInOrderBySemesterAscSubjectNameAsc(
        Long deptId, int academicYearOffered, Collection<Integer> semesters);

    // Skip-existing guard for cloning, backed by UNIQUE(course_code, academic_year_offered).
    boolean existsByCourseCodeAndAcademicYearOffered(String courseCode, int academicYearOffered);

    // Department-delete guards: block removing a department any subject still references, as
    // owner (dept_id) or via subject_eligible_departments — the FK would break otherwise.
    boolean existsByDepartment_Id(Long departmentId);
    boolean existsByEligibleDepartments_Id(Long departmentId);
}
