package com.college.backlog.model;

import jakarta.persistence.*;
import org.hibernate.annotations.BatchSize;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "registrations")
public class Registration {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "reg_id", unique = true)
    private String regId;

    @ManyToOne
    @JoinColumn(name = "roll_no")
    private Student student;

    // @BatchSize: the paginated admin list fetch-joins only the ManyToOne relations (student,
    // examCycle), keeping pagination a real SQL LIMIT — a collection fetch-join would force
    // in-memory paging. `subjects` then loads lazily during mapping, which is why that mapping
    // must stay inside RegistrationService.listSummaries' transaction (open-in-view is off);
    // BatchSize collapses those N loads into a few IN queries per page.
    @ManyToMany
    @BatchSize(size = 30)
    @JoinTable(
        name = "registration_subjects",
        joinColumns = @JoinColumn(
            name = "reg_id",
            referencedColumnName = "reg_id"
        ),
        inverseJoinColumns = @JoinColumn(name = "subject_id")
    )
    private List<Subject> subjects;

    @Column(name = "registered_at")
    private LocalDateTime registeredAt;

    // Stored as the enum name (varchar), with a DB CHECK constraint guarding the values.
    @Enumerated(EnumType.STRING)
    private RegistrationStatus status;

    @Column(name = "verified_by")
    private String verifiedBy;

    // NOT NULL lives at the DB level (folded into the Flyway V1 baseline), not here: under
    // ddl-auto=validate the mapping must match the schema, so constraints belong in a V__
    // migration, never in nullable=false.
    @ManyToOne
    @JoinColumn(name = "exam_cycle_id")
    private ExamCycle examCycle;

    @Version
    private Long version;

    // immutable snapshot at submission time, so the printed form stays faithful even after the
    // Student record changes
    @Column(name = "snap_name")
    private String snapName;

    @Column(name = "snap_email")
    private String snapEmail;

    @Column(name = "snap_phone")
    private String snapPhone;

    @Column(name = "snap_branch")
    private String snapBranch;

    @Column(name = "snap_semester")
    private Integer snapSemester;

    @Column(name = "snap_year_of_joining")
    private Integer snapYearOfJoining;

    // Academic-year offering registered against, captured at submission. Set only when all
    // subjects share one year (the usual single-semester case); null if the selection spans years.
    @Column(name = "snap_academic_year")
    private Integer snapAcademicYear;

    public Registration() {}

    public Registration(Long id, String regId, Student student, List<Subject> subjects, LocalDateTime registeredAt, RegistrationStatus status) {
        this.id = id;
        this.regId = regId;
        this.student = student;
        this.subjects = subjects;
        this.registeredAt = registeredAt;
        this.status = status;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRegId() { return regId; }
    public void setRegId(String regId) { this.regId = regId; }

    public Student getStudent() { return student; }
    public void setStudent(Student student) { this.student = student; }

    public List<Subject> getSubjects() { return subjects; }
    public void setSubjects(List<Subject> subjects) { this.subjects = subjects; }

    public LocalDateTime getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(LocalDateTime registeredAt) { this.registeredAt = registeredAt; }

    public RegistrationStatus getStatus() { return status; }
    public void setStatus(RegistrationStatus status) { this.status = status; }

    public String getVerifiedBy() { return verifiedBy; }
    public void setVerifiedBy(String verifiedBy) { this.verifiedBy = verifiedBy; }

    public ExamCycle getExamCycle() { return examCycle; }
    public void setExamCycle(ExamCycle examCycle) { this.examCycle = examCycle; }

    public Long getVersion() { return version; }
    public void setVersion(Long version) { this.version = version; }

    public String getSnapName() { return snapName; }
    public void setSnapName(String snapName) { this.snapName = snapName; }

    public String getSnapEmail() { return snapEmail; }
    public void setSnapEmail(String snapEmail) { this.snapEmail = snapEmail; }

    public String getSnapPhone() { return snapPhone; }
    public void setSnapPhone(String snapPhone) { this.snapPhone = snapPhone; }

    public String getSnapBranch() { return snapBranch; }
    public void setSnapBranch(String snapBranch) { this.snapBranch = snapBranch; }

    public Integer getSnapSemester() { return snapSemester; }
    public void setSnapSemester(Integer snapSemester) { this.snapSemester = snapSemester; }

    public Integer getSnapYearOfJoining() { return snapYearOfJoining; }
    public void setSnapYearOfJoining(Integer snapYearOfJoining) { this.snapYearOfJoining = snapYearOfJoining; }

    public Integer getSnapAcademicYear() { return snapAcademicYear; }
    public void setSnapAcademicYear(Integer snapAcademicYear) { this.snapAcademicYear = snapAcademicYear; }
}
