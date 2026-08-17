package com.college.backlog.model;

import jakarta.persistence.*;
import org.hibernate.annotations.ColumnDefault;
import java.time.LocalDate;

@Entity
@Table(name = "students")
public class Student {

    @Id
    @Column(name = "roll_no")
    private String rollNo;

    private String name;
    private String email;
    private String phone;

    // Login credential alongside the USN. Populated out-of-band (admin/import), never returned.
    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "year_of_joining")
    private int yearOfJoining;

    @Column(name = "current_semester")
    private int currentSemester;

    // Semester the student entered the programme here: 1 for a normal intake, >1 for a
    // lateral-entry/migrant who joined mid-degree (3 for a 2nd-year transfer). IS the eligibility
    // floor ({entry..current}), so they're never offered semesters they never studied here.
    // Odd, 1..7 (Semesters.isEntrySemester) — entry is always at the start of an academic year. @ColumnDefault("1") mirrors the column's `default 1` in the Flyway
    // V1 baseline so the mapping matches under ddl-auto=validate; NOT NULL is enforced at the DB
    // level (also folded into V1), never via nullable=false. See docs/adr/backlog-progression.md.
    @Column(name = "entry_semester")
    @ColumnDefault("1")
    private int entrySemester = 1;

    private String branch;

    // No all-args constructor on purpose: rollNo/name/email/phone are four adjacent Strings, so a
    // positional swap compiles and throws nothing. Build with setters (as StudentManagementService
    // already does) — each line names its own field. Don't reintroduce one.
    public Student() {}

    public String getRollNo() { return rollNo; }
    public void setRollNo(String rollNo) { this.rollNo = rollNo; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }

    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }

    public int getYearOfJoining() { return yearOfJoining; }
    public void setYearOfJoining(int yearOfJoining) { this.yearOfJoining = yearOfJoining; }

    public int getCurrentSemester() { return currentSemester; }
    public void setCurrentSemester(int currentSemester) { this.currentSemester = currentSemester; }

    public int getEntrySemester() { return entrySemester; }
    public void setEntrySemester(int entrySemester) { this.entrySemester = entrySemester; }

    public String getBranch() { return branch; }
    public void setBranch(String branch) { this.branch = branch; }

}
