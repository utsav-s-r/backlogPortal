package com.college.backlog.controller;

import com.college.backlog.controller.dto.ExamCycleRequest;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.repository.ExamCycleRepository;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.List;

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

    // The one deliberate widening: every admin role plus PROCTOR needs to READ the list, because
    // the registrations page's cycle filter is built from it. Reading which cycles exist changes
    // nothing; only the writes below are restricted.
    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN', 'PRINCIPAL', 'HOD', 'DEPT_OFFICE', 'PROCTOR')")
    public List<ExamCycle> list() {
        return examCycleRepository.findAllByOrderByCreatedAtDesc();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExamCycle create(@Valid @RequestBody ExamCycleRequest request) {
        ExamCycle cycle = new ExamCycle(request.getName(), request.getExamMonthYear());
        return examCycleRepository.save(cycle);
    }

    // Opens registrations for exactly this cycle: close whatever is open, then open the target,
    // atomically — there is never more than one active cycle.
    @PutMapping("/{id}/activate")
    @Transactional
    public ExamCycle activate(@PathVariable Long id) {
        ExamCycle target = examCycleRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam cycle not found: " + id));
        examCycleRepository.deactivateAll();
        target.setActive(true);
        return examCycleRepository.save(target);
    }

    // Ends the cycle. With no active cycle the portal reports registrations as closed.
    @PutMapping("/{id}/deactivate")
    @Transactional
    public ExamCycle deactivate(@PathVariable Long id) {
        ExamCycle target = examCycleRepository.findById(id)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Exam cycle not found: " + id));
        target.setActive(false);
        return examCycleRepository.save(target);
    }
}
