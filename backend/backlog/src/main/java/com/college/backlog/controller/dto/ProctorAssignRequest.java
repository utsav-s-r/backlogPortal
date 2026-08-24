package com.college.backlog.controller.dto;

import jakarta.validation.constraints.NotEmpty;

import java.util.List;

// Batch claim/assign. `proctor` is the target's username: required for HOD/ADMIN/PRINCIPAL, and
// for a PROCTOR caller must be absent or their own (self-claim only) — validated in the service,
// not here, because the rule is conditional on the caller's role.
public class ProctorAssignRequest {

    // @NotEmpty, not @NotNull: the latter admits {"rollNos": []}, which would run a zero-row batch
    // and answer 200. Load-bearing only while the handler keeps @Valid — see ProctorAssignmentController.
    // No trailing period: GlobalExceptionHandler joins field messages and appends one, so adding it
    // here renders "No students selected..". Without it the body is byte-identical to the
    // ResponseStatusException this replaced.
    @NotEmpty(message = "No students selected")
    private List<String> rollNos;
    private String proctor;

    public List<String> getRollNos() { return rollNos; }
    public void setRollNos(List<String> rollNos) { this.rollNos = rollNos; }

    public String getProctor() { return proctor; }
    public void setProctor(String proctor) { this.proctor = proctor; }
}
