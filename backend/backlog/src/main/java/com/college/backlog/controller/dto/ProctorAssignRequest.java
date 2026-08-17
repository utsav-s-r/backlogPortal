package com.college.backlog.controller.dto;

import java.util.List;

// Batch claim/assign. `proctor` is the target's username: required for HOD/ADMIN/PRINCIPAL, and
// for a PROCTOR caller must be absent or their own (self-claim only).
public class ProctorAssignRequest {
    private List<String> rollNos;
    private String proctor;

    public List<String> getRollNos() { return rollNos; }
    public void setRollNos(List<String> rollNos) { this.rollNos = rollNos; }

    public String getProctor() { return proctor; }
    public void setProctor(String proctor) { this.proctor = proctor; }
}
