package com.college.backlog.service;

import com.college.backlog.controller.dto.StudentCreateRequest;
import com.college.backlog.controller.dto.StudentUpdateRequest;
import com.college.backlog.model.Department;
import com.college.backlog.model.Student;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;

/**
 * Write path for student accounts — validation + persistence in one place, shared by single-create
 * and per-row bulk import. Enforces only the data rules (USN format, known branch,
 * 1 ≤ entry ≤ current ≤ 8, DOB present); authorization and dept scoping live in
 * {@code StudentManagementController}. Sensitive actions are audit-logged like ProgressionService,
 * but never the DOB itself — only that a reset happened. See docs/adr/backlog-progression.md.
 */
@Service
public class StudentManagementService {

    private static final Logger log = LoggerFactory.getLogger(StudentManagementService.class);

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private RegistrationRepository registrationRepository;

    @Autowired
    private ProgressionService progressionService;

    /**
     * Create one student, in its own transaction so a bad row can't poison a bulk import.
     * The caller has already enforced USN uniqueness and department scope.
     *
     * @throws IllegalArgumentException on any validation failure
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Student createStudent(StudentCreateRequest req) {
        String rollNo = normalizeUsn(req.getRollNo());
        validateUsn(rollNo);
        Department dept = resolveBranchDept(rollNo);
        validateSemesters(req.getCurrentSemester(), req.getEntrySemester());
        if (req.getDateOfBirth() == null) {
            throw new IllegalArgumentException("Date of birth is required.");
        }
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }

        Student s = new Student();
        s.setRollNo(rollNo);
        s.setName(req.getName().trim());
        // email is system-managed, never client-supplied
        s.setEmail(institutionalEmail(rollNo));
        s.setPhone(trimToNull(req.getPhone()));
        s.setDateOfBirth(req.getDateOfBirth());
        s.setCurrentSemester(req.getCurrentSemester());
        s.setEntrySemester(req.getEntrySemester());
        // the stable 2-letter CODE, not the editable dept name: it survives a rename and is what
        // the delete guard matches on (existsByBranchIgnoreCase)
        s.setBranch(dept.getCode());
        s.setYearOfJoining(Usn.admissionYear(rollNo));

        Student saved = studentRepository.save(s);
        log.info("STUDENT_CREATE rollNo={} currentSem={} entrySem={}",
                rollNo, saved.getCurrentSemester(), saved.getEntrySemester());

        // Seed the FULL timeline up front: entry..8, mapped linearly from the admission year
        // (1-2 -> join year, 3-4 -> +1, ...); pre-entry sems stay empty for lateral entrants.
        // Write-once and does NOT touch currentSemester (set on the form, advanced on the
        // Progression page), so a year-back is fixed by editing the affected sems there.
        // The CSV import funnels through here too.
        progressionService.backfillLinear(rollNo);
        return saved;
    }

    /** Apply an edit to an already-loaded student. DOB and USN are not touched here. */
    @Transactional
    public Student updateStudent(Student existing, StudentUpdateRequest req) {
        validateSemesters(req.getCurrentSemester(), req.getEntrySemester());
        if (req.getName() == null || req.getName().isBlank()) {
            throw new IllegalArgumentException("Name is required.");
        }
        existing.setName(req.getName().trim());
        // email stays system-managed; re-derive so legacy rows self-heal
        existing.setEmail(institutionalEmail(existing.getRollNo()));
        existing.setPhone(trimToNull(req.getPhone()));
        existing.setCurrentSemester(req.getCurrentSemester());
        existing.setEntrySemester(req.getEntrySemester());
        Student saved = studentRepository.save(existing);
        log.info("STUDENT_UPDATE rollNo={} currentSem={} entrySem={}",
                saved.getRollNo(), saved.getCurrentSemester(), saved.getEntrySemester());
        return saved;
    }

    /** Reset the login credential (DOB). The value is never logged. */
    @Transactional
    public void resetDob(Student existing, LocalDate dateOfBirth) {
        if (dateOfBirth == null) {
            throw new IllegalArgumentException("Date of birth is required.");
        }
        existing.setDateOfBirth(dateOfBirth);
        studentRepository.save(existing);
        log.info("STUDENT_DOB_RESET rollNo={}", existing.getRollNo());
    }

    /** Delete a student that no registration references; otherwise reject. */
    @Transactional
    public void deleteStudent(Student existing) {
        if (registrationRepository.existsByStudent_RollNo(existing.getRollNo())) {
            throw new IllegalStateException(
                "This student has registrations and cannot be deleted.");
        }
        studentRepository.delete(existing);
        log.info("STUDENT_DELETE rollNo={}", existing.getRollNo());
    }

    // ---- validation helpers (also reused by the controller for dry-run import) ----

    public String normalizeUsn(String rollNo) {
        return rollNo == null ? "" : rollNo.trim().toUpperCase();
    }

    public void validateUsn(String rollNo) {
        if (!Usn.isValid(rollNo)) {
            throw new IllegalArgumentException("USN must be in the format 1MS22CS001.");
        }
    }

    /** Resolve and validate the department from the USN's branch code. */
    public Department resolveBranchDept(String rollNo) {
        String branchCode = Usn.branchCode(rollNo);
        return departmentRepository.findByCodeIgnoreCase(branchCode)
            .orElseThrow(() -> new IllegalArgumentException(
                "Unknown branch code '" + branchCode + "' in USN. Add the department first."));
    }

    /**
     * Parity is deliberate, not cosmetic: a year is a semester PAIR, so a student sits in an even
     * semester and joins at an odd one. Progression's +2 preserves it. The one place both are
     * written — create, update and import all route here.
     */
    public void validateSemesters(int currentSemester, int entrySemester) {
        Semesters.assertCurrentSemester(currentSemester);
        Semesters.assertEntrySemester(entrySemester);
        if (entrySemester > currentSemester) {
            throw new IllegalArgumentException(
                "Entry semester (" + entrySemester + ") cannot be after the current semester ("
                    + currentSemester + ").");
        }
    }

    /** Blank-safe trim. Static and public so BulkProgressionService shares it rather than
     *  keeping a third byte-identical copy. */
    public static String trimToNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /** Always {@code <usn>@msrit.edu} — never client-editable. */
    private String institutionalEmail(String rollNo) {
        return rollNo.toLowerCase() + "@msrit.edu";
    }
}
