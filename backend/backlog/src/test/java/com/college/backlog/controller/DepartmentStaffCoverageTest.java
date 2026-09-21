package com.college.backlog.controller;

import com.college.backlog.model.Department;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code hasVerifier} on the departments list: does this department hold an account that can
 * verify its own students' registrations.
 *
 * <p>Only the STUDENT's department may verify, and PRINCIPAL is absent from that endpoint
 * entirely, so a department with students and no HOD or office has a queue nobody is watching.
 * ADMIN can still act college-wide — it is a silent stall, not a dead end, and the stall is the
 * problem: the student sees "pending", the row says their department verifies, and no one there
 * exists to look. Nothing is refused; any setup order stays legal and the flag clears itself.
 *
 * <p>The fixture reproduces the live case exactly — CV has students and a PROCTOR but no HOD or
 * office — which is why the PROCTOR exclusion is asserted rather than assumed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DepartmentStaffCoverageTest {

    private static final String DEPARTMENTS = "/api/admin/departments";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;

    @BeforeEach
    void seed() {
        AdminAuthorizationFixture.seed(userRepository, departmentRepository,
                studentRepository, assignmentRepository, subjectRepository);
    }

    /** JSONPath filters go through the CODE, not the id: an {@code @GeneratedValue} id differs per
     *  run and {@code $[?(@.id==N)]} resolves to null here. */
    private String flagOf(String code, String flag) {
        return "$[?(@.code=='" + code + "')]." + flag;
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aDepartmentWithAnHodAndAnOfficeIsCovered() throws Exception {
        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(flagOf(CS_CODE, "hasStudents"), contains(true)))
                .andExpect(jsonPath(flagOf(CS_CODE, "hasVerifier"), contains(true)));
    }

    /**
     * THE case the flag exists for, and the one live today. A proctor verifies their ASSIGNED
     * students, not the department's — counting one would report coverage this department does
     * not have, so {@code VERIFYING_DEPT_ROLES} leaves PROCTOR out. CV holds one, and must still
     * come back uncovered.
     */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aDepartmentWithStudentsAndOnlyAProctorIsNotCovered() throws Exception {
        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(flagOf(CV_CODE, "hasStudents"), contains(true)))
                .andExpect(jsonPath(flagOf(CV_CODE, "hasVerifier"), contains(false)));
    }

    /**
     * The control that stops the flag being a constant: strip CS of both verifying roles and it
     * must flip. Without this every assertion above passes on a hardcoded {@code true}.
     */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void removingTheLastHodAndOfficeUncoversTheDepartment() throws Exception {
        userRepository.delete(userRepository.findByUsername(HOD).orElseThrow());
        userRepository.delete(userRepository.findByUsername(DEPT_OFFICE).orElseThrow());
        userRepository.flush();

        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(flagOf(CS_CODE, "hasStudents"), contains(true)))
                .andExpect(jsonPath(flagOf(CS_CODE, "hasVerifier"), contains(false)));
    }

    /** One of the two is enough — the page warns on "no verifier at all", not "no HOD". */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void anOfficeAloneStillCoversTheDepartment() throws Exception {
        userRepository.delete(userRepository.findByUsername(HOD).orElseThrow());
        userRepository.flush();

        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(flagOf(CS_CODE, "hasVerifier"), contains(true)));
    }

    /**
     * Staff but no students yet — covered, and the page must not warn. The warning is the PAIR
     * ({@code hasStudents && !hasVerifier}), so a department in either half-built state is
     * ordinary, not a problem: creating a department, loading a cohort and appointing staff may
     * happen in any order.
     */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aDepartmentWithStaffAndNoStudentsIsNotFlagged() throws Exception {
        Department ae = new Department();
        ae.setDeptName("Aeronautical Engineering");
        ae.setCode("AE");
        Department saved = departmentRepository.saveAndFlush(ae);
        User hod = new User();
        hod.setUsername("hod-ae-user");
        hod.setPassword("x");
        hod.setRole(UserRole.HOD);
        hod.setDepartment(saved);
        userRepository.saveAndFlush(hod);

        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(flagOf("AE", "hasStudents"), contains(false)))
                .andExpect(jsonPath(flagOf("AE", "hasVerifier"), contains(true)));
    }

    /** The empty half-built state too: neither students nor staff, and nothing to warn about. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aBrandNewEmptyDepartmentIsNotFlagged() throws Exception {
        Department me = new Department();
        me.setDeptName("Mechanical Engineering");
        me.setCode("ME");
        departmentRepository.saveAndFlush(me);

        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath(flagOf("ME", "hasStudents"), contains(false)))
                .andExpect(jsonPath(flagOf("ME", "hasVerifier"), contains(false)));
    }
}
