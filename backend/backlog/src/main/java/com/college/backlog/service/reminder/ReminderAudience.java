package com.college.backlog.service.reminder;

import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Student;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.service.Emails;
import com.college.backlog.service.Usn;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Who a reminder goes to, resolved NOW: one entry per student holding a VERIFIED registration in
 * the cycle, narrowed to one department by the student's USN branch code — the same key that
 * decides who may verify them. Its own bean so the transaction applies (the runner calls it
 * across a proxy).
 */
@Service
public class ReminderAudience {

    public record Recipient(String rollNo, String name, String email, List<String> subjects) {}

    @Autowired
    private RegistrationRepository registrationRepository;

    @Transactional(readOnly = true)
    public List<Recipient> resolve(Long examCycleId, String departmentCode) {
        Map<String, Student> students = new LinkedHashMap<>();
        Map<String, TreeSet<String>> subjects = new LinkedHashMap<>();
        for (Registration reg : registrationRepository.findByExamCycle_IdAndStatus(
                examCycleId, RegistrationStatus.VERIFIED)) {
            Student s = reg.getStudent();
            if (s == null) {
                continue;
            }
            if (departmentCode != null && !departmentCode.equalsIgnoreCase(Usn.branchCode(s.getRollNo()))) {
                continue;
            }
            students.putIfAbsent(s.getRollNo(), s);
            TreeSet<String> set = subjects.computeIfAbsent(s.getRollNo(), k -> new TreeSet<>());
            reg.getSubjects().forEach(sub -> set.add(sub.getSubjectName() + " (" + sub.getCourseCode() + ")"));
        }
        List<Recipient> out = new ArrayList<>();
        // students.name is nullable (V1): a null would print "Dear null" and break the send
        students.forEach((rollNo, s) -> out.add(new Recipient(rollNo,
                s.getName() == null || s.getName().isBlank() ? rollNo : s.getName().strip(),
                s.getEmail() == null || s.getEmail().isBlank() ? Emails.institutional(rollNo) : s.getEmail(),
                List.copyOf(subjects.get(rollNo)))));
        out.sort(Comparator.comparing(Recipient::rollNo));
        return out;
    }
}
