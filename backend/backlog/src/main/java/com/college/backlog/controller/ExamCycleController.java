package com.college.backlog.controller;

import com.college.backlog.controller.dto.ExamCycleListItem;
import com.college.backlog.controller.dto.ExamCycleRequest;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.BatchLine;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.User;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.service.Constraints;
import com.college.backlog.service.AdminAuditService;
import com.college.backlog.service.CallerScope;
import org.springframework.security.core.Authentication;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

@RestController
@RequestMapping("/api/admin/exam-cycles")
// ADMIN-only by DEFAULT, deliberately: an ExamCycle has no department — it is the college-wide
// registration switch, so activate/deactivate opens or closes registration for every department at
// once. That is not a departmental power, and it is not a PRINCIPAL/HOD/DEPT_OFFICE one either.
// The default is the narrow one so a method added later without its own annotation inherits
// ADMIN-only rather than a wide set — the previous shape was the inverse, which is exactly how
// DEPT_OFFICE ended up able to close registration college-wide.
@PreAuthorize("hasRole('ADMIN')")
public class ExamCycleController {

    @Autowired
    private ExamCycleRepository examCycleRepository;

    @Autowired
    private RegistrationRepository registrationRepository;

    // Until P3-9 these writes resolved NO caller at all — the college-wide registration switch
    // could be thrown with nothing recording who did it. Both are needed now: the actor for the
    // audit row, and requireActor's own 401 on an account whose row is gone.
    @Autowired
    private CallerScope callerScope;

    @Autowired
    private AdminAuditService auditService;

    // The one deliberate widening: every admin role plus PROCTOR needs to READ the list, because
    // the registrations page's cycle filter is built from it. Reading which cycles exist changes
    // nothing; only the writes below are restricted.
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public List<ExamCycleListItem> list() {
        List<ExamCycle> cycles = examCycleRepository.findAllByOrderByCreatedAtDesc();
        // One query for the whole list, not existsByExamCycle_Id per row. Skipped when empty: an
        // empty IN list is not valid SQL.
        Set<Long> referenced = cycles.isEmpty() ? Set.of()
            : registrationRepository.findReferencedExamCycleIds(
                cycles.stream().map(ExamCycle::getId).toList());
        return cycles.stream()
            .map(c -> ExamCycleListItem.of(c, referenced.contains(c.getId())))
            .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ExamCycle create(@Valid @RequestBody ExamCycleRequest request, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        ExamCycle cycle = new ExamCycle(request.getName(), request.getExamMonthYear());
        // Absent (not empty) means copy the previous cycle's batch list: it changes by a year at a
        // time, and re-typing eight lines per cycle is how it ends up missing or stale. An empty
        // list is taken literally — "this cycle prints no batch block".
        cycle.setBatchLines(request.getBatchLines() != null
                ? toBatchLines(request)
                : examCycleRepository.findFirstByOrderByCreatedAtDesc()
                    .map(previous -> new ArrayList<>(previous.getBatchLines()))
                    .orElseGet(ArrayList::new));
        ExamCycle saved = examCycleRepository.save(cycle);
        auditService.record(AdminAuditAction.EXAM_CYCLE_CREATE, actor, AuditTargetType.EXAM_CYCLE,
                String.valueOf(saved.getId()), "name=" + saved.getName());
        return saved;
    }

    /**
     * Correct a cycle's name or exam month — the two fields that are wrong on screen if they were
     * mistyped, and there was no way to fix either.
     *
     * <p>Refused once a registration references the cycle. Both fields are read LIVE: the
     * registrations table shows the name, the printed form the month. Unlike a subject there is
     * nothing here that is NOT displayed, so the refusal is the whole row rather than a per-field
     * comparison — and a no-op resave has nothing to save either way.
     */
    @PutMapping("/{id}")
    @Transactional
    public ExamCycle update(@PathVariable Long id, @Valid @RequestBody ExamCycleRequest request,
                            Authentication auth) {
        User actor = callerScope.requireActor(auth);
        ExamCycle target = examCycleRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam cycle not found: " + id));
        if (registrationRepository.existsByExamCycle_Id(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Students have registered under this cycle, so its name and exam month can no "
                    + "longer be changed — they appear on forms that have already been submitted.");
        }
        target.setName(request.getName());
        target.setExamMonthYear(request.getExamMonthYear());
        // Replace the whole block rather than diffing: the editor saves the list as one thing, and
        // @ElementCollection rewrites the rows anyway. Absent means "leave the list alone", so a
        // caller that only renames a cycle cannot silently drop its batch list.
        if (request.getBatchLines() != null) {
            target.getBatchLines().clear();
            target.getBatchLines().addAll(toBatchLines(request));
        }
        ExamCycle saved;
        try {
            // saveAndFlush so the name clash surfaces here rather than at an unrelated later flush
            saved = examCycleRepository.saveAndFlush(target);
        } catch (DataIntegrityViolationException e) {
            // Only the name index means "duplicate" — anything else falls through to
            // GlobalExceptionHandler's generic 409, which also logs it.
            if (!Constraints.isViolationOf(e, Constraints.EXAM_CYCLE_NAME)) {
                throw e;
            }
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                "Another exam cycle is already named '" + request.getName() + "'.");
        }
        auditService.record(AdminAuditAction.EXAM_CYCLE_UPDATE, actor, AuditTargetType.EXAM_CYCLE,
                String.valueOf(id), "name=" + saved.getName() + " month=" + saved.getExamMonthYear()
                    + " batchLines=" + saved.getBatchLines().size());
        return saved;
    }

    private static List<BatchLine> toBatchLines(ExamCycleRequest request) {
        List<BatchLine> lines = new ArrayList<>();
        for (ExamCycleRequest.BatchLineRequest line : request.getBatchLines()) {
            lines.add(new BatchLine(line.getLabel().trim(), line.getBatch().trim()));
        }
        return lines;
    }

    // Opens registrations for exactly this cycle: close whatever is open, then open the target,
    // atomically — there is never more than one active cycle.
    @PutMapping("/{id}/activate")
    @Transactional
    public ExamCycle activate(@PathVariable Long id, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        ExamCycle target = examCycleRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam cycle not found: " + id));
        // Already open (a stale tab, a repeated call): nothing changes, so no write and no audit row
        // claiming registration was opened.
        if (target.isActive()) {
            return target;
        }
        examCycleRepository.deactivateAllExcept(id);
        target.setActive(true);
        ExamCycle saved = examCycleRepository.save(target);
        // Inside the method's existing transaction: opening registration college-wide can never
        // commit without the row naming who opened it.
        auditService.record(AdminAuditAction.EXAM_CYCLE_ACTIVATE, actor, AuditTargetType.EXAM_CYCLE,
                String.valueOf(id), "name=" + saved.getName() + " (registration OPEN)");
        return saved;
    }

    // Ends the cycle. With no active cycle the portal reports registrations as closed.
    @PutMapping("/{id}/deactivate")
    @Transactional
    public ExamCycle deactivate(@PathVariable Long id, Authentication auth) {
        User actor = callerScope.requireActor(auth);
        ExamCycle target = examCycleRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam cycle not found: " + id));
        target.setActive(false);
        ExamCycle saved = examCycleRepository.save(target);
        auditService.record(AdminAuditAction.EXAM_CYCLE_DEACTIVATE, actor, AuditTargetType.EXAM_CYCLE,
                String.valueOf(id), "name=" + saved.getName() + " (registration CLOSED)");
        return saved;
    }
}
