package com.college.backlog.controller;

import com.college.backlog.controller.dto.PhoneUpdateRequest;
import com.college.backlog.controller.dto.RegistrationSummaryResponse;
import com.college.backlog.controller.dto.StudentProfileResponse;
import com.college.backlog.controller.dto.StudentSubjectsResponse;
import com.college.backlog.model.Department;
import com.college.backlog.model.Registration;
import com.college.backlog.model.Student;
import com.college.backlog.model.StudentSemesterTerm;
import com.college.backlog.model.Subject;
import com.college.backlog.model.SubjectType;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.StudentSemesterTermRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.service.EligibilityService;
import com.college.backlog.service.PdfService;
import com.college.backlog.service.Usn;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/student")
@PreAuthorize("hasRole('STUDENT')")
public class StudentController {

    @Autowired
    private StudentRepository studentRepository;

    @Autowired
    private RegistrationRepository registrationRepository;

    @Autowired
    private PdfService pdfService;

    @Autowired
    private DepartmentRepository departmentRepository;

    @Autowired
    private EligibilityService eligibilityService;

    @Autowired
    private StudentSemesterTermRepository studentSemesterTermRepository;

    @Autowired
    private SubjectRepository subjectRepository;

    private Student currentStudent(Authentication auth) {
        return studentRepository.findByRollNo(auth.getName())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Student account not found"));
    }

    @GetMapping("/me")
    public StudentProfileResponse getProfile(Authentication authentication) {
        return toProfile(currentStudent(authentication));
    }

    // Subjects registerable for a backlog semester. The academic year is NOT client-supplied —
    // it comes from the progression record (the year they first studied that semester), so a
    // retake always shows that original year's offering.
    @GetMapping("/subjects")
    public StudentSubjectsResponse subjectsForSemester(@RequestParam int semester,
                                                       Authentication authentication) {
        Student student = currentStudent(authentication);

        // semester must be in the eligibility window (defence in depth — the UI already
        // constrains the dropdown)
        if (!eligibilityService.isEligible(student.getCurrentSemester(), student.getEntrySemester(), semester)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "You are not eligible to register backlogs for semester " + semester + ".");
        }

        // fail closed: with no progression record the year's offering is unknown, so refuse
        // rather than guess
        StudentSemesterTerm term = studentSemesterTermRepository
            .findByRollNoAndSemester(student.getRollNo(), semester)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.CONFLICT,
                "We don't have a record of the academic year you studied semester " + semester
                    + ". Please contact the department office."));

        int academicYear = term.getAcademicYear();
        String branchCode = Usn.branchCode(student.getRollNo());
        List<Subject> subjects = subjectRepository
            .findBySemesterAndAcademicYearOffered(semester, academicYear).stream()
            .filter(s -> branchMatches(s, branchCode))
            .collect(Collectors.toList());

        return new StudentSubjectsResponse(semester, academicYear, subjects);
    }

    // A regular subject belongs to the student's own department; an elective needs that
    // department in its eligible list. Matched on the immutable 2-letter branch code (USN /
    // dept_code), never the display name, so a rename can't break eligibility.
    private boolean branchMatches(Subject subject, String branchCode) {
        if (branchCode == null) {
            return false;
        }
        if (subject.getSubjectType() == SubjectType.ELECTIVE) {
            return subject.getEligibleDepartments().stream()
                .anyMatch(d -> branchCode.equalsIgnoreCase(d.getCode()));
        }
        return subject.getDepartment() != null
            && branchCode.equalsIgnoreCase(subject.getDepartment().getCode());
    }

    @PutMapping("/me/phone")
    public StudentProfileResponse updatePhone(@Valid @RequestBody PhoneUpdateRequest request,
                                              Authentication authentication) {
        Student s = currentStudent(authentication);
        s.setPhone(request.getPhone());
        studentRepository.save(s);
        return toProfile(s);
    }

    // Branch derives from the USN's 2-letter code (single source of truth), not the stored
    // student row. currentSemester stays master data and is never written during registration.
    private StudentProfileResponse toProfile(Student s) {
        return new StudentProfileResponse(
            s.getRollNo(), s.getName(), s.getEmail(),
            deriveBranch(s.getRollNo()), s.getPhone(), s.getCurrentSemester(),
            new ArrayList<>(eligibilityService.eligibleSemesters(s.getCurrentSemester(), s.getEntrySemester())));
    }

    private String deriveBranch(String rollNo) {
        String code = Usn.branchCode(rollNo);
        if (code == null) {
            return null;
        }
        return departmentRepository.findByCodeIgnoreCase(code)
            .map(Department::getDeptName)
            .orElse(null);
    }

    @GetMapping("/registrations")
    public List<RegistrationSummaryResponse> myRegistrations(Authentication authentication) {
        return registrationRepository.findByStudent_RollNo(authentication.getName()).stream()
            .sorted(Comparator.comparing(Registration::getRegisteredAt).reversed())
            .map(reg -> new RegistrationSummaryResponse(
                reg.getRegId(),
                reg.getStudent().getRollNo(),
                // snapshot only, never the live student row: these are NOT NULL as of V7, and
                // falling back would print today's values on an old registration
                reg.getSnapName(),
                reg.getSnapSemester(),
                reg.getSnapYearOfJoining(),
                reg.getSubjects().stream()
                    .map(s -> s.getSubjectName() + " (" + s.getCourseCode() + ")")
                    .collect(Collectors.toList()),
                reg.getStatus().name(),
                reg.getRegisteredAt().toString(),
                reg.getVerifiedBy(),
                reg.getExamCycle() != null ? reg.getExamCycle().getName() : null))
            .collect(Collectors.toList());
    }

    @GetMapping("/registrations/{regId}/pdf")
    public ResponseEntity<byte[]> downloadOwnPdf(@PathVariable String regId,
                                                 Authentication authentication) throws Exception {
        Registration reg = registrationRepository.findByRegId(regId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found with ID: " + regId));

        // ownership: a student may only download their own form. 404 not 403, so probing can't
        // confirm a regId exists.
        if (reg.getStudent() == null
                || !authentication.getName().equals(reg.getStudent().getRollNo())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Registration not found with ID: " + regId);
        }

        byte[] pdfBytes = pdfService.generateRegistrationPdf(reg);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDispositionFormData("attachment",
            "backlog-registration-" + reg.getStudent().getRollNo() + ".pdf");

        return ResponseEntity.ok().headers(headers).body(pdfBytes);
    }
}
