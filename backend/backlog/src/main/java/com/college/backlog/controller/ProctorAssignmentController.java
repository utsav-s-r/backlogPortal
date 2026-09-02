package com.college.backlog.controller;

import com.college.backlog.controller.dto.*;
import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.Student;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.service.AdminAuditService;
import org.springframework.transaction.annotation.Transactional;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.service.StudentManagementService;
import com.college.backlog.service.StudentSpecification;
import com.college.backlog.service.Usn;
import com.college.backlog.service.Batches;
import com.college.backlog.service.CallerScope;
import jakarta.validation.Valid;
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
    private AdminAuditService auditService;

    @Autowired
    private CallerScope callerScope;

    private static final Logger log = LoggerFactory.getLogger(ProctorAssignmentController.class);

    // Same page guards as the student roster list.
    private static final int MAX_PAGE_SIZE = 200;
    private static final int DEFAULT_PAGE_SIZE = 25;
    // Same bound as bulk progression: a claim batch is an explicit, bounded list.

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
                a != null && a.getProctorUserId().equals(target.getId()));
        });
    }

    // ---- assigned list (management view) ----

    @GetMapping("/students")
    public List<AssignedStudentResponse> assigned(@RequestParam Optional<String> proctor,
                                                  Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = resolveTargetProctor(actor, proctor.orElse(null));
        List<ProctorAssignment> assignments =
            assignmentRepository.findByProctorUserId(target.getId());
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
    public BatchResult<ProgressionRowResult> assign(@Valid @RequestBody ProctorAssignRequest req, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        User target = resolveTargetProctor(actor, req.getProctor());
        String deptCode = requireDeptCode(target);

        // Null and empty are refused at binding by @NotEmpty on the DTO (400,
        // {"message": "No students selected."}). @Valid is what makes that annotation live;
        // dropping it turns an empty batch into a 200 no-op.
        List<String> rollNos = req.getRollNos();
        Batches.assertWithinLimit(rollNos.size(), "students");

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
                    if (existing.get().getProctorUserId().equals(target.getId())) {
                        results.add(new ProgressionRowResult(roll, null, "SKIPPED_EXISTS",
                            "already under this proctor"));
                        skipped++;
                    } else {
                        // the one place the current holder is named — the proctor needs to know
                        // who to ask, or the HOD who to reassign from. Resolved from the id (V4)
                        // only on this conflict path, so the happy path stays one query.
                        results.add(new ProgressionRowResult(roll, null, "ERROR",
                            "Already assigned to " + holderName(existing.get()) + "."));
                        errors++;
                    }
                    continue;
                }
                assignmentRepository.save(new ProctorAssignment(roll, target.getId(), actor.getUsername()));
                results.add(new ProgressionRowResult(roll, null, "CREATED", null));
                assigned++;
            } catch (NumberFormatException e) {
                // NFE extends IllegalArgumentException, so without this clause it lands below and
                // its raw message reaches the admin as if the ROW were bad — a server bug dressed as
                // a data problem. Must precede the IAE clause; the reverse does not compile. NOT the
                // catch-all's wording: "it may have just been claimed" names a race, which for an
                // NFE is a false explanation.
                log.error("PROCTOR_ASSIGN_ROW_FAILED rollNo={}", roll, e);
                results.add(new ProgressionRowResult(roll, null, "ERROR",
                    "Could not assign this student."));
                errors++;
            } catch (IllegalArgumentException e) {
                // Only this loop's own validation throws IAE, with curated literal messages, so
                // surfacing getMessage() is safe here.
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
        return new BatchResult<>(false, assigned, skipped, errors, results);
    }

    // ---- remove from supervision ----

    @DeleteMapping("/assignments/{rollNo}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unassign(@PathVariable String rollNo, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        String roll = studentService.normalizeUsn(rollNo);
        ProctorAssignment assignment = assignmentRepository.findById(roll)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                "This student has no proctor assignment."));

        if (actor.getRole() == UserRole.PROCTOR
                && !assignment.getProctorUserId().equals(actor.getId())) {
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
        // Records which proctor lost the student — the assignment row is about to be gone, and the
        // audit row carries no FK precisely so it survives that.
        auditService.record(AdminAuditAction.PROCTOR_UNASSIGN, actor,
                AuditTargetType.PROCTOR_ASSIGNMENT, roll,
                "proctor=" + holderName(assignment));
        assignmentRepository.delete(assignment);
    }

    // ---- helpers ----

    /**
     * The username behind an assignment's {@code proctor_user_id} (V4), for messages and audit
     * detail that must name a person rather than an id. Falls back to the id if the row is gone —
     * the FK cascades, so that is not reachable today, but a message is the wrong place to 500.
     */
    private String holderName(ProctorAssignment assignment) {
        return userRepository.findById(assignment.getProctorUserId())
            .map(User::getUsername)
            .orElseGet(() -> "#" + assignment.getProctorUserId());
    }

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
        User target = userRepository.findByUsername(proctorParam.trim())
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
