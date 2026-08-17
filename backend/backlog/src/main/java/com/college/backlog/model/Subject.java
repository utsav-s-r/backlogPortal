package com.college.backlog.model;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.ColumnDefault;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "subjects")
public class Subject {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "subject_name")
    private String subjectName;

    // Composite UNIQUE(course_code, academic_year_offered) at the DB level — a course recurs
    // across years, so course_code alone is intentionally NOT unique.
    @Column(name = "course_code")
    private String courseCode;

    private int semester;

    private int credits;

    // Year this offering was taught (2023 = AY 2023-24) — the authoritative key for backlog
    // year-binding. @ColumnDefault("0") mirrors the column's `default 0` in the Flyway V1 baseline
    // so the mapping matches under ddl-auto=validate; NOT NULL is enforced at the DB level (also
    // folded into V1), never via nullable=false. See docs/adr/backlog-progression.md.
    @Column(name = "academic_year_offered")
    @ColumnDefault("0")
    private int academicYearOffered;

    @ManyToOne
    @JoinColumn(name = "dept_id")
    private Department department;

    // Stored as the enum name (varchar), with a DB CHECK constraint guarding the values.
    @Enumerated(EnumType.STRING)
    @Column(name = "subject_type")
    private SubjectType subjectType = SubjectType.REGULAR;

    // @BatchSize: EAGER means every subject hydration loads this collection, so N subjects would
    // issue N join-table queries. Batching collapses them into a few INs (as Registration.subjects).
    @ManyToMany(fetch = FetchType.EAGER)
    @BatchSize(size = 50)
    @JoinTable(
        name = "subject_eligible_departments",
        joinColumns = @JoinColumn(name = "subject_id"),
        inverseJoinColumns = @JoinColumn(name = "dept_id")
    )
    private List<Department> eligibleDepartments = new ArrayList<>();

    // No all-args constructor on purpose: semester/credits/academicYearOffered are three adjacent
    // ints with overlapping domains, so a positional swap compiles, throws nothing, and persists a
    // plausible wrong row (a subject filed under the wrong semester silently skews the eligibility
    // window). Build with setters — each line names its own field. Don't reintroduce one.
    public Subject() {}

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSubjectName() { return subjectName; }
    public void setSubjectName(String subjectName) { this.subjectName = subjectName; }

    public String getCourseCode() { return courseCode; }
    public void setCourseCode(String courseCode) { this.courseCode = courseCode; }

    public int getSemester() { return semester; }
    public void setSemester(int semester) { this.semester = semester; }

    public int getCredits() { return credits; }
    public void setCredits(int credits) { this.credits = credits; }

    public int getAcademicYearOffered() { return academicYearOffered; }
    public void setAcademicYearOffered(int academicYearOffered) { this.academicYearOffered = academicYearOffered; }

    public Department getDepartment() { return department; }
    public void setDepartment(Department department) { this.department = department; }

    public SubjectType getSubjectType() { return subjectType; }
    public void setSubjectType(SubjectType subjectType) { this.subjectType = subjectType; }

    public List<Department> getEligibleDepartments() { return eligibleDepartments; }
    public void setEligibleDepartments(List<Department> eligibleDepartments) { this.eligibleDepartments = eligibleDepartments; }
}
