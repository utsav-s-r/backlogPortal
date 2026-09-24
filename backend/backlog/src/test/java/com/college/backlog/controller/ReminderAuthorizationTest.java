package com.college.backlog.controller;

import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.ReminderStatus;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.ReminderRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import com.college.backlog.service.reminder.ReminderAudience;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reminders email students college-wide, so every /api/admin/reminders endpoint is ADMIN-only and
 * the class-level @PreAuthorize is the only control: SecurityConfig's /api/admin/** rule admits all
 * five staff roles. Also pins the audience rule against real rows — VERIFIED only, this cycle only,
 * department by the student's USN branch — and the trigger's feature-off 404.
 *
 * <p>Registrations are seeded HERE, never in AdminAuthorizationFixture (they make students and
 * subjects undeletable and would break other suites' delete cases).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ReminderAuthorizationTest {

    private static final String BASE = "/api/admin/reminders";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;
    @Autowired private ReminderRepository reminderRepository;
    @Autowired private ReminderAudience audience;

    private Ids ids;
    private Long cycleId;
    private Long otherCycleId;

    @BeforeEach
    void seed() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
        ExamCycle cycle = examCycleRepository.save(new ExamCycle("Reminder Cycle", "2026-06"));
        ExamCycle other = examCycleRepository.save(new ExamCycle("Other Reminder Cycle", "2026-12"));
        cycleId = cycle.getId();
        otherCycleId = other.getId();
        Subject cs = subjectRepository.findById(ids.csSubjectId).orElseThrow();
        Subject cv = subjectRepository.findById(ids.cvSubjectId).orElseThrow();

        registration("REM-1", CS_ASSIGNED_A, cs, cycle, RegistrationStatus.VERIFIED);
        registration("REM-2", CS_ASSIGNED_B, cs, cycle, RegistrationStatus.SUBMITTED);
        registration("REM-3", CS_UNASSIGNED, cs, cycle, RegistrationStatus.REJECTED);
        registration("REM-4", CV_STUDENT, cv, cycle, RegistrationStatus.VERIFIED);
        registration("REM-5", CS_ASSIGNED_B, cs, other, RegistrationStatus.VERIFIED);
    }

    private void registration(String regId, String rollNo, Subject subject, ExamCycle cycle,
                              RegistrationStatus status) {
        Student student = studentRepository.findByRollNo(rollNo).orElseThrow();
        Registration r = new Registration();
        r.setRegId(regId);
        r.setStudent(student);
        r.setSubjects(List.of(subject));
        r.setExamCycle(cycle);
        r.setStatus(status);
        r.setRegisteredAt(Instant.now());
        r.setSnapName(student.getName());
        r.setSnapBranch(student.getBranch());
        r.setSnapSemester(student.getCurrentSemester());
        r.setSnapYearOfJoining(student.getYearOfJoining());
        registrationRepository.save(r);
    }

    private String body(Long deptId, String sendAt) {
        return "{\"examCycleId\":" + cycleId + ",\"departmentId\":" + deptId
                + ",\"sendAt\":\"" + sendAt + "\",\"subject\":\"Hall tickets\",\"message\":\"Collect them.\"}";
    }

    private static String tomorrowIst() {
        return LocalDateTime.now(java.time.ZoneId.of("Asia/Kolkata")).plusDays(1).withSecond(0).withNano(0)
                .toString().substring(0, 16);
    }

    // ---- audience: the rule the whole feature rests on ----

    @Test
    void audienceIsVerifiedRegistrationsOfThisCycleOnly() {
        assertThat(audience.resolve(cycleId, null))
                .extracting(ReminderAudience.Recipient::rollNo)
                .containsExactly(CS_ASSIGNED_A, CV_STUDENT);
    }

    @Test
    void aDepartmentNarrowsByTheStudentsUsnBranch() {
        List<ReminderAudience.Recipient> cs = audience.resolve(cycleId, CS_CODE);

        assertThat(cs).extracting(ReminderAudience.Recipient::rollNo).containsExactly(CS_ASSIGNED_A);
        assertThat(cs.get(0).subjects()).singleElement().asString().contains("(" + CS_SUBJECT_CODE + ")");
        assertThat(cs.get(0).email()).isNotBlank();
        assertThat(audience.resolve(otherCycleId, CV_CODE)).isEmpty();
    }

    // ---- ADMIN may use every endpoint ----

    @Test
    void adminPreviewsCountsAndSchedulesThenCancels() throws Exception {
        mockMvc.perform(post(BASE + "/preview").with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recipientCount").value(2));

        String created = mockMvc.perform(post(BASE).with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(ids.csDeptId, tomorrowIst())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("SCHEDULED"))
                .andExpect(jsonPath("$.departmentCode").value(CS_CODE))
                .andReturn().getResponse().getContentAsString();
        long id = Long.parseLong(created.replaceAll(".*\"id\":(\\d+).*", "$1"));

        mockMvc.perform(post(BASE + "/" + id + "/cancel").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().isOk());
        assertThat(reminderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(ReminderStatus.CANCELLED);
        // cancelled is final
        mockMvc.perform(post(BASE + "/" + id + "/cancel").with(user(ADMIN).roles("ADMIN")).with(csrf()))
                .andExpect(status().isConflict());
    }

    @Test
    void aSendTimeInThePastIsRefused() throws Exception {
        mockMvc.perform(post(BASE).with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(body(null, "2020-01-01T09:00")))
                .andExpect(status().isBadRequest());
    }

    /** Unconfigured mail (the test profile): refused before anything is attempted. */
    @Test
    void aTestSendWithoutMailConfiguredIsAConflict() throws Exception {
        mockMvc.perform(post(BASE + "/test").with(user(ADMIN).roles("ADMIN")).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"me@gmail.com\",\"subject\":\"s\",\"message\":\"m\"}"))
                .andExpect(status().isConflict());
    }

    // ---- every other staff role is refused every endpoint ----

    @Test
    void everyNonAdminRoleIsForbiddenEverywhere() throws Exception {
        String[][] cast = {{PRINCIPAL, "PRINCIPAL"}, {HOD, "HOD"}, {DEPT_OFFICE, "DEPT_OFFICE"}, {PROCTOR, "PROCTOR"}};
        for (String[] who : cast) {
            for (RequestBuilder req : requestsAs(who[0], who[1])) {
                // exactly 403, never "not 2xx": a 401 would mean the user row was missing, which proves nothing
                mockMvc.perform(req).andExpect(status().isForbidden());
            }
        }
        assertThat(reminderRepository.count()).isZero();
    }

    private List<RequestBuilder> requestsAs(String username, String role) {
        var u = user(username).roles(role);
        return List.of(
                get(BASE).with(u),
                get(BASE + "/config").with(u),
                get(BASE + "/1/failures").with(u),
                post(BASE).with(u).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(null, tomorrowIst())),
                post(BASE + "/preview").with(u).with(csrf()).contentType(MediaType.APPLICATION_JSON).content(body(null, "")),
                post(BASE + "/1/cancel").with(u).with(csrf()),
                post(BASE + "/test").with(u).with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"to\":\"me@gmail.com\",\"subject\":\"s\",\"message\":\"m\"}"));
    }

    // ---- the cron door with no token configured (the test profile) ----

    @Test
    void theTriggerIsOffWithoutAToken() throws Exception {
        mockMvc.perform(post("/api/internal/reminders/run").header("X-Cron-Token", "anything"))
                .andExpect(status().isNotFound());
    }
}
