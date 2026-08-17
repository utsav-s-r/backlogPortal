package com.college.backlog.model;

import jakarta.persistence.*;

/**
 * The academic year a student FIRST studied a semester — the authoritative source for backlog
 * year-binding: a sem-N backlog resolves to the subjects offered in {@code academicYear} no matter
 * when it is retaken. One row per (rollNo, semester), enforced by the unique constraint, which is
 * what makes writes first-studied / write-once. See docs/adr/backlog-progression.md.
 */
@Entity
@Table(
    name = "student_semester_terms",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_student_semester",
        columnNames = {"roll_no", "semester"}
    )
)
public class StudentSemesterTerm {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "roll_no", nullable = false)
    private String rollNo;

    // 1..8
    @Column(nullable = false)
    private int semester;

    // academic-year start, e.g. 2023 = AY 2023-24
    @Column(name = "academic_year", nullable = false)
    private int academicYear;

    public StudentSemesterTerm() {}

    public StudentSemesterTerm(String rollNo, int semester, int academicYear) {
        this.rollNo = rollNo;
        this.semester = semester;
        this.academicYear = academicYear;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public int getSemester() { return semester; }
    public void setSemester(int semester) { this.semester = semester; }

    public int getAcademicYear() { return academicYear; }
    public void setAcademicYear(int academicYear) { this.academicYear = academicYear; }
}
