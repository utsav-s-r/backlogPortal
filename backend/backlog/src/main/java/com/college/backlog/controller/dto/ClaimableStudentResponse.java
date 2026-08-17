package com.college.backlog.controller.dto;

// One row of the proctor claim picker — deliberately thinner than StudentSummaryResponse. The
// picker is the only window a proctor gets onto students they don't supervise, so it carries just
// enough to identify and claim: no email/phone, no progression state, and no current proctor's
// name (a claim conflict names the holder in its per-row error instead).
public class ClaimableStudentResponse {
    private final String rollNo;
    private final String name;
    private final int currentSemester;
    // already supervised by someone — a claim would 409
    private final boolean proctored;
    // supervised by the target proctor themselves
    private final boolean mine;

    public ClaimableStudentResponse(String rollNo, String name, int currentSemester,
                                    boolean proctored, boolean mine) {
        this.rollNo = rollNo;
        this.name = name;
        this.currentSemester = currentSemester;
        this.proctored = proctored;
        this.mine = mine;
    }

    public String getRollNo() { return rollNo; }
    public String getName() { return name; }
    public int getCurrentSemester() { return currentSemester; }
    public boolean isProctored() { return proctored; }
    public boolean isMine() { return mine; }
}
