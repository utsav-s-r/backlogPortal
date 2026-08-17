package com.college.backlog.controller;

import com.college.backlog.controller.dto.*;
import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.Student;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.service.StudentManagementService;
import com.college.backlog.service.StudentSpecification;
import com.college.backlog.service.Usn;
import com.college.backlog.service.CallerScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Proctor supervision assignments. A PROCTOR self-serves via the claim picker; HOD (own dept) and
 * ADMIN/PRINCIPAL manage any proctor's list by naming a target. One proctor per student — claiming
 * a supervised student fails per-row naming the current proctor, and reassignment is
 * unassign + assign by HOD/admin.
 *
 * The picker is the ONLY window a proctor gets onto students outside their set, and returns a
 * minimal projection (ClaimableStudentResponse); everything else stays hard-scoped (fail closed).
 * DEPT_OFFICE has no access — proctor management is HOD and above.
 */
@RestController
@RequestMapping("/api/admin/proctor")
@PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'PROCTOR')")
public class ProctorAssignmentController {

    @Autowired
    private CallerScope callerScope;

    private static final Logger log = LoggerFactory.getLogger(ProctorAssignmentController.class);

    // Same page guards as the student roster list.
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;
    // Same bound as bulk progression: a claim batch is an explicit, bounded list.
    private static final int MAX_BATCH = 500;

    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private UserRepository userRepository;
    @Autowired private StudentManagementService studentService;

    // ---- claim picker ----

    @GetMapping("/claimable")
    public Page<ClaimableStudentResponse> claimable(
            @RequestParam Optional<Integer> admissionYear,
            @RequestParam Optional<Integer> semester,
            @RequestParam Optional<String> query,
            @RequestParam Optional<String> proctor,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_PAGE_SIZE) int size,
            Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = resolveTargetProctor(actor, proctor.orElse(null));
        String deptCode = requireDeptCode(target);

        String rollNoLike = usnPattern(deptCode, admissionYear.orElse(null));
        StudentSpecification spec =
            new StudentSpecification(rollNoLike, semester.orElse(null), query.orElse(null));
        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by("rollNo"));
        Page<Student> students = studentRepository.findAll(spec, pageable);

        Map<String, ProctorAssignment> byRoll = assignmentsByRoll(
            students.getContent().stream().map(Student::getRollNo).collect(Collectors.toList()));

        return students.map(s -> {
            ProctorAssignment a = byRoll.get(s.getRollNo());
            return new ClaimableStudentResponse(
                s.getRollNo(), s.getName(), s.getCurrentSemester(),
                a != null,
                a != null && a.getProctorUsername().equals(target.getUsername()));
        });
    }

    // ---- assigned list (management view) ----

    @GetMapping("/students")
    public List<AssignedStudentResponse> assigned(@RequestParam Optional<String> proctor,
                                                  Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = resolveTargetProctor(actor, proctor.orElse(null));
        List<ProctorAssignment> assignments =
            assignmentRepository.findByProctorUsername(target.getUsername());
        if (assignments.isEmpty()) return List.of();

        Map<String, Student> students = studentRepository.findByRollNoInOrderByRollNo(
                assignments.stream().map(ProctorAssignment::getRollNo).collect(Collectors.toList()))
            .stream().collect(Collectors.toMap(Student::getRollNo, Function.identity()));

        return assignments.stream()
            .sorted(Comparator.comparing(ProctorAssignment::getRollNo))
            .map(a -> {
                Student s = students.get(a.getRollNo());
                return new AssignedStudentResponse(
                    a.getRollNo(),
                    s != null ? s.getName() : null,
                    s != null ? s.getCurrentSemester() : 0,
                    a.getAssignedBy(),
                    a.getAssignedAt() != null ? a.getAssignedAt().toString() : null);
            })
            .collect(Collectors.toList());
    }

    // ---- claim / assign (batch) ----

    @PostMapping("/assignments")
    public BatchResult assign(@RequestBody ProctorAssignRequest req, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = resolveTargetProctor(actor, req.getProctor());
        String deptCode = requireDeptCode(target);

        List<String> rollNos = req.getRollNos() == null ? List.of() : req.getRollNos();
        if (rollNos.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No students selected.");
        }
        if (rollNos.size() > MAX_BATCH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "At most " + MAX_BATCH + " students per batch.");
        }

        List<ProgressionRowResult> results = new ArrayList<>();
        int assigned = 0, skipped = 0, errors = 0;
        for (String raw : rollNos) {
            String roll = studentService.normalizeUsn(raw);
            try {
                if (!deptCode.equalsIgnoreCase(Usn.branchCode(roll) == null ? "" : Usn.branchCode(roll))) {
                    throw new IllegalArgumentException("Outside the proctor's department.");
                }
                if (!studentRepository.existsById(roll)) {
                    throw new IllegalArgumentException("Student not found: " + roll);
                }
                Optional<ProctorAssignment> existing = assignmentRepository.findById(roll);
                if (existing.isPresent()) {
                    if (existing.get().getProctorUsername().equals(target.getUsername())) {
                        results.add(new ProgressionRowResult(roll, null, "SKIPPED_EXISTS",
                            "already under this proctor"));
                        skipped++;
                    } else {
                        // the one place the current holder is named — the proctor needs to know
                        // who to ask, or the HOD who to reassign from
                        results.add(new ProgressionRowResult(roll, null, "ERROR",
                            "Already assigned to " + existing.get().getProctorUsername() + "."));
                        errors++;
                    }
                    continue;
                }
                assignmentRepository.save(new ProctorAssignment(roll, target.getUsername(), actor.getUsername()));
                results.add(new ProgressionRowResult(roll, null, "CREATED", null));
                assigned++;
            } catch (IllegalArgumentException e) {
                results.add(new ProgressionRowResult(roll, null, "ERROR", e.getMessage()));
                errors++;
            } catch (ResponseStatusException e) {
                // a nested 403/409 (e.g. out-of-scope student) carries the actionable sentence;
                // the generic catch below would flatten it, RSE being a RuntimeException
                results.add(new ProgressionRowResult(roll, null, "ERROR", e.getReason()));
                errors++;
            } catch (RuntimeException e) {
                // e.g. two proctors racing on one student: the PK on roll_no makes the second save
                // a constraint violation — reported per-row, never aborting the batch. Logged: the
                // race is the expected cause, but nothing else would record any other cause.
                log.error("PROCTOR_ASSIGN_ROW_FAILED rollNo={}", roll, e);
                results.add(new ProgressionRowResult(roll, null, "ERROR",
                    "Could not assign this student (it may have just been claimed)."));
                errors++;
            }
        }
        return new BatchResult(false, assigned, skipped, errors, results);
    }

    // ---- remove from supervision ----

    @DeleteMapping("/assignments/{rollNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unassign(@PathVariable String rollNo, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        String roll = studentService.normalizeUsn(rollNo);
        ProctorAssignment assignment = assignmentRepository.findById(roll)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This student has no proctor assignment."));

        if (actor.getRole() == UserRole.PROCTOR
                && !assignment.getProctorUsername().equals(actor.getUsername())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This student is not under your supervision.");
        }
        if (actor.getRole() == UserRole.HOD) {
            String deptCode = requireDeptCode(actor);
            if (!deptCode.equalsIgnoreCase(Usn.branchCode(roll) == null ? "" : Usn.branchCode(roll))) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Outside your department's scope.");
            }
        }
        assignmentRepository.delete(assignment);
    }

    // ---- helpers ----

    /** Whose assignment list is read/written: PROCTOR only themselves, HOD proctors of their own
     *  department, ADMIN/PRINCIPAL any proctor. */
    private User resolveTargetProctor(User actor, String proctorParam) {
        if (actor.getRole() == UserRole.PROCTOR) {
            if (proctorParam != null && !proctorParam.isBlank()
                    && !proctorParam.equals(actor.getUsername())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only manage your own students.");
            }
            return actor;
        }
        if (proctorParam == null || proctorParam.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "A target proctor is required.");
        }
        User target = userRepository.findById(proctorParam.trim())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Proctor not found."));
        if (target.getRole() != UserRole.PROCTOR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "'" + target.getUsername() + "' is not a proctor account.");
        }
        if (actor.getRole() == UserRole.HOD) {
            Long actorDept = actor.getDepartment() != null ? actor.getDepartment().getId() : null;
            Long targetDept = target.getDepartment() != null ? target.getDepartment().getId() : null;
            if (actorDept == null || !actorDept.equals(targetDept)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "You can only manage proctors of your own department.");
            }
        }
        return target;
    }

    /** The dept code the target proctor is pinned to; a proctor without one is misconfigured. */
    private String requireDeptCode(User user) {
        if (user.getDepartment() == null || user.getDepartment().getCode() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "No department assigned to this account.");
        }
        return user.getDepartment().getCode();
    }

    /** USN LIKE pattern from dept code + optional admission year (same rule as the roster list). */
    private String usnPattern(String deptCode, Integer admissionYear) {
        String yy = admissionYear != null ? String.format("%02d", admissionYear % 100) : "__";
        return "1MS" + yy + deptCode.toUpperCase() + "%";
    }

    private Map<String, ProctorAssignment> assignmentsByRoll(List<String> rollNos) {
        if (rollNos.isEmpty()) return Map.of();
        return assignmentRepository.findByRollNoIn(rollNos).stream()
            .collect(Collectors.toMap(ProctorAssignment::getRollNo, Function.identity()));
    }
}
