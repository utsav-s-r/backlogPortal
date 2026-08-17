package com.college.backlog.service;

import com.college.backlog.controller.dto.RegistrationSummaryResponse;
import com.college.backlog.model.*;
import com.college.backlog.repository.*;
import jakarta.persistence.EntityManager;
import jakarta.persistence.criteria.CriteriaBuilder;
import jakarta.persistence.criteria.CriteriaQuery;
import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class RegistrationService {

    /** Max pending (SUBMITTED) registrations per student per exam cycle. At 1 the partial unique
     *  index uq_pending_reg_per_cycle (roll_no, exam_cycle_id) WHERE status='SUBMITTED' enforces
     *  it at the DB level too. */
    private static final int MAX_PENDING_PER_CYCLE = 1;

    @Autowired
    private RegistrationRepository registrationRepository;

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    @Autowired
    private ExamCycleRepository examCycleRepository;

    @Autowired
    private RegistrationEventRepository registrationEventRepository;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EligibilityService eligibilityService;

    @Autowired
    private StudentSemesterTermRepository studentSemesterTermRepository;

    @Autowired
    private EntityManager entityManager;

    /**
     * Status counts for the admin dashboard cards: one {@code GROUP BY status} over the filtered
     * set, not one count query per status. {@code countDistinct} on the id keeps counts right when
     * the spec's subjects join fans out rows. Statuses with no rows are absent from the map.
     */
    public java.util.Map<RegistrationStatus, Long> countGroupedByStatus(Specification<Registration> spec) {
        CriteriaBuilder cb = entityManager.getCriteriaBuilder();
        CriteriaQuery<Object[]> query = cb.createQuery(Object[].class);
        Root<Registration> root = query.from(Registration.class);
        Predicate predicate = spec.toPredicate(root, query, cb);
        query.multiselect(root.get("status"), cb.countDistinct(root.get("id")));
        if (predicate != null) {
            query.where(predicate);
        }
        query.groupBy(root.get("status"));

        java.util.Map<RegistrationStatus, Long> counts = new java.util.EnumMap<>(RegistrationStatus.class);
        for (Object[] row : entityManager.createQuery(query).getResultList()) {
            counts.put((RegistrationStatus) row[0], (Long) row[1]);
        }
        return counts;
    }

    /**
     * Paginated admin list, mapped to DTOs INSIDE the transaction — required, not stylistic.
     * The paginated query deliberately does not fetch-join {@code subjects} (a collection fetch
     * makes Hibernate paginate in memory instead of emitting SQL LIMIT), so {@code getSubjects()}
     * is a lazy load; mapping in the controller would only work with open-in-view, which is off.
     * {@code @BatchSize(30)} collapses the per-row loads. {@code readOnly} skips dirty-check/flush.
     */
    @Transactional(readOnly = true)
    public Page<RegistrationSummaryResponse> listSummaries(Specification<Registration> spec, Pageable pageable) {
        return registrationRepository.findAll(spec, pageable).map(this::toSummary);
    }

    /** Private on purpose: touches lazy state, so it must not be reachable from a controller
     *  (see {@link #listSummaries}). */
    private RegistrationSummaryResponse toSummary(Registration reg) {
        // Snapshot columns only — NOT NULL as of V7. The old `snap != null ? snap : student.get()`
        // fallbacks silently printed the LIVE student row on an old registration, inverting the
        // immutable-history convention this table exists to uphold.
        return new RegistrationSummaryResponse(
            reg.getRegId(),
            reg.getStudent().getRollNo(),
            reg.getSnapName(),
            reg.getSnapSemester(),
            reg.getSnapYearOfJoining(),
            reg.getSubjects().stream()
                .map(s -> s.getSubjectName() + " (" + s.getCourseCode() + ")")
                .collect(java.util.stream.Collectors.toList()),
            reg.getStatus().name(),
            reg.getRegisteredAt().toString(),
            reg.getVerifiedBy(),
            reg.getExamCycle() != null ? reg.getExamCycle().getName() : null);
    }

    // One transaction for the insert AND its SUBMITTED audit event, so history can never gain a
    // row without its audit trail. The unique-index backstop below still works — its catch
    // rethrows immediately and the transaction rolls back.
    //
    // Status rule for every refusal below, so the split isn't arbitrary:
    //   400 — the SUBMISSION is wrong and the student can fix it by submitting differently
    //         (malformed USN, unknown/ineligible/out-of-branch subject ids).
    //   409 — the submission is fine but the RECORD isn't ready, and only staff can resolve it
    //         (registrations closed, phone missing, current semester unset, no progression row for
    //         the semester, a pending registration already exists).
    // Keep new refusals on one side of that line rather than inventing a third code.
    @Transactional
    public Registration register(String rollNo, List<Long> subjectIds) {

        // registrations are only accepted while an exam cycle is open
        ExamCycle cycle = examCycleRepository.findByActiveTrue()
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "Registrations are currently closed. No active exam cycle."));

        // joining year and branch are encoded in the USN (1MS<YY><BR><NNN>), so validate it first
        if (!Usn.isValid(rollNo)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "USN must be in the format 1MS22CS001.");
        }

        // identity (name/email/phone) comes from the account, never the request — a USN cannot be
        // impersonated or have its record overwritten by the submission
        Student student = studentRepository.findByRollNo(rollNo)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                "Student account not found."));

        // phone is set only from the dashboard; server-side guard, not bypassable by a crafted request.
        // 409, not 400 — the submission is well-formed; it's the ACCOUNT that isn't ready. See the
        // status rule on this method.
        if (student.getPhone() == null || !student.getPhone().matches("^[0-9]{10}$")) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Add your phone number in your profile before registering.");
        }

        int yearOfJoining = Usn.admissionYear(rollNo);
        String branchCode = Usn.branchCode(rollNo);
        Department department = departmentRepository.findByCodeIgnoreCase(branchCode)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Unknown branch code '" + branchCode + "' in USN. Contact the department office."));
        String branch = department.getDeptName();

        // eligibility window derives from the admin-maintained current semester, never the request
        java.util.Set<Integer> eligibleSemesters =
            eligibilityService.eligibleSemesters(student.getCurrentSemester(), student.getEntrySemester());
        if (eligibleSemesters.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Your current semester is not set up for registration. Contact the department office.");
        }

        // validate subjects before touching the DB
        List<Subject> subjects = subjectRepository.findAllById(subjectIds);

        if (subjects.size() != subjectIds.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "One or more selected subjects are invalid.");
        }

        // one query for the whole progression (a handful of rows) instead of one per selected subject
        java.util.Map<Integer, StudentSemesterTerm> termsBySemester =
            studentSemesterTermRepository.findByRollNo(rollNo).stream()
                .collect(java.util.stream.Collectors.toMap(
                    StudentSemesterTerm::getSemester, java.util.function.Function.identity()));

        for (Subject subject : subjects) {
            // backlog window: subject's semester must be one the student may still register for
            // (mirrors the UI constraint)
            if (!eligibleSemesters.contains(subject.getSemester())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Subject '" + subject.getSubjectName() + "' is for semester "
                        + subject.getSemester() + ", which you are not eligible to register for.");
            }
            // year-binding: subject must be the offering from the academic year the student
            // actually studied that semester. Fail closed with no progression row — mirrors the
            // read path, so a crafted/stale request can't register another year's offering.
            StudentSemesterTerm term = termsBySemester.get(subject.getSemester());
            if (term == null) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "We don't have a record of the academic year you studied semester "
                        + subject.getSemester() + ". Please contact the department office.");
            }
            if (subject.getAcademicYearOffered() != term.getAcademicYear()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Subject '" + subject.getSubjectName() + "' is not from your semester "
                        + subject.getSemester() + " offering (" + term.getAcademicYear() + ").");
            }
            if (subject.getSubjectType() == SubjectType.ELECTIVE) {
                // match on the immutable 2-letter branch code, not the human name
                boolean eligible = subject.getEligibleDepartments().stream()
                    .anyMatch(d -> branchCode.equalsIgnoreCase(d.getCode()));
                if (!eligible) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Not eligible for elective: " + subject.getSubjectName());
                }
            } else {
                if (subject.getDepartment() == null ||
                    !branchCode.equalsIgnoreCase(subject.getDepartment().getCode())) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Subject '" + subject.getSubjectName() + "' does not belong to branch: " + branch);
                }
            }
        }

        // at most MAX_PENDING_PER_CYCLE pending rows per student per cycle;
        // VERIFIED/REJECTED rows don't count
        long pendingCount = registrationRepository
                .countByStudent_RollNoAndExamCycle_IdAndStatus(
                    rollNo, cycle.getId(), RegistrationStatus.SUBMITTED);
        if (pendingCount >= MAX_PENDING_PER_CYCLE) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You already have a pending registration for this exam cycle. "
                    + "Wait for it to be verified or rejected before submitting another.");
        }

        // Registration writes nothing back to the student row: it is immutable identity (set at
        // import, phone via dashboard), branch/year derive from the USN, and the per-registration
        // semester lives in the snapshot below.
        Registration reg = new Registration();
        reg.setRegId(UUID.randomUUID().toString());
        reg.setStudent(student);
        reg.setSubjects(subjects);
        reg.setRegisteredAt(LocalDateTime.now());
        reg.setStatus(RegistrationStatus.SUBMITTED);
        reg.setExamCycle(cycle);
        reg.setSnapName(student.getName());
        reg.setSnapEmail(student.getEmail());
        reg.setSnapPhone(student.getPhone());
        reg.setSnapBranch(branch);
        // "CURRENT SEMESTER OF THE STUDENT" on the printed form — the admin-maintained account
        // value, never a client one. (The backlog is for an earlier semester; the label still
        // means where the student stands now.)
        reg.setSnapSemester(student.getCurrentSemester());
        reg.setSnapYearOfJoining(yearOfJoining);
        // academic-year offering only when unambiguous; null if the selection spans years
        java.util.Set<Integer> distinctAcademicYears = subjects.stream()
            .map(Subject::getAcademicYearOffered)
            .collect(java.util.stream.Collectors.toSet());
        reg.setSnapAcademicYear(distinctAcademicYears.size() == 1
            ? distinctAcademicYears.iterator().next() : null);

        Registration saved;
        try {
            // saveAndFlush so the index violation surfaces here, not at a later flush
            saved = registrationRepository.saveAndFlush(reg);
        } catch (DataIntegrityViolationException e) {
            // race backstop: a concurrent submit that slipped past the count check above is
            // caught by the partial unique index uq_pending_reg_per_cycle. Check WHICH constraint
            // fired — this used to tell a student with zero pending registrations to wait for one.
            if (!Constraints.isViolationOf(e, Constraints.PENDING_REGISTRATION_PER_CYCLE)) {
                throw e;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "You already have a pending registration for this exam cycle. "
                    + "Wait for it to be verified or rejected before submitting another.");
        }

        registrationEventRepository.save(new RegistrationEvent(
            saved.getRegId(), EventAction.SUBMITTED, rollNo, ActorRole.STUDENT, null));

        return saved;
    }

    /**
     * Action a pending registration: status flip and audit event in ONE transaction, so a status
     * change can never commit without its event row. State is re-checked inside the transaction;
     * a concurrent action is caught by that check or by the {@code @Version} lock on flush.
     * Authorization (dept scoping, role checks) stays with the caller.
     */
    @Transactional
    public Registration applyVerification(String regId, RegistrationStatus action,
                                          String actor, ActorRole actorRole) {
        if (action != RegistrationStatus.VERIFIED && action != RegistrationStatus.REJECTED) {
            throw new IllegalArgumentException("action must be VERIFIED or REJECTED");
        }
        Registration reg = registrationRepository.findByRegId(regId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found with ID: " + regId));

        // one-way state machine; REJECTED is terminal (no un-reject, no re-verify):
        //   SUBMITTED -> VERIFIED | REJECTED
        //   VERIFIED  -> REJECTED   (override a completed verification)
        RegistrationStatus current = reg.getStatus();
        boolean allowed = (action == RegistrationStatus.VERIFIED && current == RegistrationStatus.SUBMITTED)
            || (action == RegistrationStatus.REJECTED
                && (current == RegistrationStatus.SUBMITTED || current == RegistrationStatus.VERIFIED));
        if (!allowed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This registration has already been actioned.");
        }

        reg.setStatus(action);
        if (actor != null) {
            reg.setVerifiedBy(actor);
        }
        try {
            // @Version makes a concurrent action fail here instead of silently overwriting
            registrationRepository.saveAndFlush(reg);
        } catch (OptimisticLockingFailureException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "This registration was just actioned by someone else.");
        }

        registrationEventRepository.save(new RegistrationEvent(
            reg.getRegId(), EventAction.valueOf(action.name()), actor, actorRole, null));
        return reg;
    }
}