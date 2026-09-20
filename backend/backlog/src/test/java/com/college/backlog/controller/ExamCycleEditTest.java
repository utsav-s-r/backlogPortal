package com.college.backlog.controller;

import com.college.backlog.model.BatchLine;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Student;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A cycle's name and exam month were unfixable: the controller had create, activate and deactivate
 * and nothing else, so a mistyped month stayed on every form of that cycle forever. The edit is
 * refused once a registration references the cycle — both fields are read LIVE by the
 * registrations table and the printed form, the same rule that freezes a subject's printed fields.
 *
 * <p>Full context: the class-level {@code @PreAuthorize("hasRole('ADMIN')")} and
 * {@code AccountExistenceFilter} both live in the filter chain, and a standalone MockMvc would
 * skip method security and pass whatever the annotation said.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class ExamCycleEditTest {

    private static final String CYCLES = "/api/admin/exam-cycles";
    private static final String ADMIN = "cycle-edit-admin";
    private static final String PRINCIPAL = "cycle-edit-principal";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;
    @Autowired private PasswordEncoder passwordEncoder;

    private AdminAuthorizationFixture.Ids ids;
    private Long cycleId;

    @BeforeEach
    void seed() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
        staff(ADMIN, UserRole.ADMIN);
        staff(PRINCIPAL, UserRole.PRINCIPAL);
        // The row this suite is about: free text from before the format existed.
        cycleId = examCycleRepository.save(new ExamCycle("Testing", "Not a valid month/year")).getId();
    }

    private void staff(String username, UserRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode("irrelevant"));
        user.setRole(role);
        userRepository.save(user);
    }

    private String body(String name, String examMonthYear) {
        return "{\"name\":\"" + name + "\",\"examMonthYear\":\"" + examMonthYear + "\"}";
    }

    /** Registrations are created HERE, never in the shared fixture, where one would make its
     *  student and subject undeletable and break the delete cases in other suites. */
    private void registerAStudentUnder(Long targetCycleId) {
        Student student = studentRepository.findById(AdminAuthorizationFixture.CS_ASSIGNED_A).orElseThrow();
        ExamCycle cycle = examCycleRepository.findById(targetCycleId).orElseThrow();
        Registration reg = new Registration();
        reg.setRegId("REG-CYCLE-EDIT-1");
        reg.setStudent(student);
        reg.setSubjects(List.of(subjectRepository.findById(ids.csSubjectId).orElseThrow()));
        reg.setExamCycle(cycle);
        reg.setStatus(RegistrationStatus.SUBMITTED);
        reg.setRegisteredAt(Instant.now());
        reg.setSnapName(student.getName());
        reg.setSnapBranch(student.getBranch());
        reg.setSnapSemester(student.getCurrentSemester());
        reg.setSnapYearOfJoining(student.getYearOfJoining());
        registrationRepository.saveAndFlush(reg);
    }

    // ---- the point of the endpoint ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void correctsAnUnreferencedCycle() throws Exception {
        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("June 2026 Backlog Exams", "2026-06")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.examMonthYear").value("2026-06"));

        ExamCycle saved = examCycleRepository.findById(cycleId).orElseThrow();
        assertThat(saved.getName()).isEqualTo("June 2026 Backlog Exams");
        assertThat(saved.getExamMonthYear()).isEqualTo("2026-06");
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesAnEditOnceAStudentHasRegisteredUnderTheCycle() throws Exception {
        registerAStudentUnder(cycleId);

        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Renamed After The Fact", "2026-06")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already been submitted")));

        // The refusal must leave the row alone: the entity is managed, so a guard placed after the
        // setters would still show the new values to a caller in the same transaction.
        ExamCycle unchanged = examCycleRepository.findById(cycleId).orElseThrow();
        assertThat(unchanged.getName()).isEqualTo("Testing");
        assertThat(unchanged.getExamMonthYear()).isEqualTo("Not a valid month/year");
    }

    /** The edit reuses the create DTO, so the format rule applies here too — otherwise this
     *  endpoint becomes the way back in for free text. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesAFreeTextMonthOnEditToo() throws Exception {
        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Testing", "June 2026")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("YYYY-MM")));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void reportsADuplicateNameAsSuch() throws Exception {
        examCycleRepository.save(new ExamCycle("December 2025 Backlog Exams", "2025-12"));

        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("December 2025 Backlog Exams", "2026-06")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("already named")));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void answers404ForACycleThatDoesNotExist() throws Exception {
        mockMvc.perform(put(CYCLES + "/999999").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Whatever", "2026-06")))
                .andExpect(status().isNotFound());
    }

    // ---- the batch list ----

    private static String lines(String... labelBatchPairs) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < labelBatchPairs.length; i += 2) {
            if (i > 0) sb.append(",");
            sb.append("{\"label\":\"").append(labelBatchPairs[i])
              .append("\",\"batch\":\"").append(labelBatchPairs[i + 1]).append("\"}");
        }
        return sb.append("]").toString();
    }

    private String bodyWithLines(String name, String examMonthYear, String batchLinesJson) {
        return "{\"name\":\"" + name + "\",\"examMonthYear\":\"" + examMonthYear
                + "\",\"batchLines\":" + batchLinesJson + "}";
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void savesTheBatchListInTheOrderGiven() throws Exception {
        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithLines("Testing", "2026-06",
                                lines("B.E. I to VII Semester", "2021",
                                      "M.TECH. I to IV Semester", "2022 & 2023"))))
                .andExpect(status().isOk());

        List<BatchLine> saved = examCycleRepository.findById(cycleId).orElseThrow().getBatchLines();
        // Order is the printed order, so it is part of the data — not incidental.
        assertThat(saved).extracting(BatchLine::getLabel)
                .containsExactly("B.E. I to VII Semester", "M.TECH. I to IV Semester");
        assertThat(saved).extracting(BatchLine::getBatch).containsExactly("2021", "2022 & 2023");
    }

    /** Absent, not empty: a caller renaming a cycle must not silently drop its batch list. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void leavesTheBatchListAloneWhenTheRequestOmitsIt() throws Exception {
        ExamCycle withLines = examCycleRepository.findById(cycleId).orElseThrow();
        withLines.setBatchLines(List.of(new BatchLine("B.E. I to VII Semester", "2021")));
        examCycleRepository.saveAndFlush(withLines);

        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Renamed", "2026-06")))
                .andExpect(status().isOk());

        assertThat(examCycleRepository.findById(cycleId).orElseThrow().getBatchLines()).hasSize(1);
    }

    /** The form is one page: an unbounded list pushes the student's details off it, and nothing
     *  else would notice until someone printed one. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesMoreLinesThanTheFormCanHold() throws Exception {
        String[] thirteen = new String[26];
        for (int i = 0; i < 13; i++) {
            thirteen[i * 2] = "Programme " + i;
            thirteen[i * 2 + 1] = "202" + (i % 10);
        }

        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithLines("Testing", "2026-06", lines(thirteen))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at most 12")));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesABatchLineWithAnEmptyHalf() throws Exception {
        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithLines("Testing", "2026-06",
                                lines("B.E. I to VII Semester", ""))))
                .andExpect(status().isBadRequest());
    }

    /** The lines print on the form, so they freeze with the rest of the cycle. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesABatchListEditOnceTheCycleHasRegistrations() throws Exception {
        registerAStudentUnder(cycleId);

        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithLines("Testing", "2026-06",
                                lines("Rewritten After The Fact", "2021"))))
                .andExpect(status().isConflict());

        assertThat(examCycleRepository.findById(cycleId).orElseThrow().getBatchLines()).isEmpty();
    }

    /** Re-typing eight lines per cycle is how the list ends up missing or stale, so a new cycle
     *  inherits the most recent one's. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aNewCycleInheritsThePreviousCyclesBatchList() throws Exception {
        ExamCycle previous = examCycleRepository.findById(cycleId).orElseThrow();
        previous.setBatchLines(List.of(new BatchLine("B.E. I to VII Semester", "2021")));
        examCycleRepository.saveAndFlush(previous);

        mockMvc.perform(post(CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Supplementary 2027", "2027-01")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchLines[0].label").value("B.E. I to VII Semester"))
                .andExpect(jsonPath("$.batchLines[0].batch").value("2021"));
    }

    /** An EMPTY list is taken literally — "this cycle prints no batch block" — or a cycle could
     *  never be given one without lines. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void anExplicitlyEmptyListIsNotTreatedAsAbsent() throws Exception {
        ExamCycle previous = examCycleRepository.findById(cycleId).orElseThrow();
        previous.setBatchLines(List.of(new BatchLine("B.E. I to VII Semester", "2021")));
        examCycleRepository.saveAndFlush(previous);

        mockMvc.perform(post(CYCLES).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithLines("Supplementary 2028", "2028-01", "[]")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.batchLines").isEmpty());
    }

    // ---- authorization ----

    /** The cycle is the college-wide registration switch, so this controller is ADMIN-only by
     *  default and a method added without its own annotation inherits that. Asserted because
     *  nothing else would notice if the edit were widened. */
    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void deniesTheEditToPrincipal() throws Exception {
        mockMvc.perform(put(CYCLES + "/" + cycleId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Principal Was Here", "2026-06")))
                .andExpect(status().isForbidden());

        assertThat(examCycleRepository.findById(cycleId).orElseThrow().getName()).isEqualTo("Testing");
    }

    // ---- the UI signal ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void theListFlagsExactlyTheReferencedCycles() throws Exception {
        examCycleRepository.save(new ExamCycle("Spare Cycle", "2027-01"));
        registerAStudentUnder(cycleId);

        mockMvc.perform(get(CYCLES))
                .andExpect(status().isOk())
                // Both directions in one request: a hardcoded flag fails one of them.
                .andExpect(jsonPath("$[?(@.name=='Testing')].referenced", contains(true)))
                .andExpect(jsonPath("$[?(@.name=='Spare Cycle')].referenced", contains(false)));
    }
}
