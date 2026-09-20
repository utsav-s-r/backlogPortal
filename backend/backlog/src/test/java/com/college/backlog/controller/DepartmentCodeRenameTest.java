package com.college.backlog.controller;

import com.college.backlog.model.Department;
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
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A department's CODE is the branch segment of every one of its students' USNs (1MS24CS001), and
 * since 2026-09-20 it is also what decides which department may VERIFY their registrations. So it
 * is fixed once students exist: rewriting {@code students.branch} to follow a rename would desync
 * the column from the USN it is derived from, and leaving it would strip that department's HOD
 * and office of authority over their own students until someone repaired the column by hand.
 *
 * <p>Raised by the auth-scope audit of the scope change — the endpoint predates it, but the change
 * is what turned a filtering annoyance into a loss of authority.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class DepartmentCodeRenameTest {

    private static final String DEPARTMENTS = "/api/admin/departments";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;

    private Long csDeptId;
    private Long emptyDeptId;

    @BeforeEach
    void seed() {
        Ids ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository,
                studentRepository, assignmentRepository, subjectRepository);
        csDeptId = ids.csDeptId;          // CS — the fixture's students are 1MS24CS…
        Department empty = new Department();
        empty.setDeptName("Aeronautical Engineering");
        empty.setCode("AE");              // no students carry AE
        emptyDeptId = departmentRepository.saveAndFlush(empty).getId();
    }

    private String body(String name, String code) {
        return "{\"deptName\":\"" + name + "\",\"code\":\"" + code + "\"}";
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void refusesACodeChangeOnADepartmentThatHasStudents() throws Exception {
        mockMvc.perform(put(DEPARTMENTS + "/" + csDeptId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Computer Science", "CE")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("USNs contain that code")));

        assertThat(departmentRepository.findById(csDeptId).orElseThrow().getCode()).isEqualTo(CS_CODE);
    }

    /**
     * The control that matters most: the guard must not freeze the whole row. Renaming the
     * DISPLAY name is the common edit and stays allowed while students exist.
     */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void allowsRenamingTheDepartmentItselfWhileKeepingTheCode() throws Exception {
        mockMvc.perform(put(DEPARTMENTS + "/" + csDeptId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Computer Science & Engineering", CS_CODE)))
                .andExpect(status().isOk());

        Department saved = departmentRepository.findById(csDeptId).orElseThrow();
        assertThat(saved.getDeptName()).isEqualTo("Computer Science & Engineering");
        assertThat(saved.getCode()).isEqualTo(CS_CODE);
    }

    /** Case alone is not a change — the handler upper-cases before comparing. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void acceptsTheSameCodeInLowerCase() throws Exception {
        mockMvc.perform(put(DEPARTMENTS + "/" + csDeptId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Computer Science", CS_CODE.toLowerCase())))
                .andExpect(status().isOk());
    }

    /** A department nobody has been admitted into can still be corrected — a typo'd code at
     *  creation is exactly when this has to work. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void allowsACodeChangeWhileTheDepartmentHasNoStudents() throws Exception {
        mockMvc.perform(put(DEPARTMENTS + "/" + emptyDeptId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Aeronautical Engineering", "AN")))
                .andExpect(status().isOk());

        assertThat(departmentRepository.findById(emptyDeptId).orElseThrow().getCode()).isEqualTo("AN");
    }

    /** The flag the page locks the input on. Both directions in one request, so a hardcoded
     *  value fails one of them. */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void theListSaysWhichDepartmentsHaveStudents() throws Exception {
        mockMvc.perform(get(DEPARTMENTS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='" + CS_CODE + "')].hasStudents", contains(true)))
                .andExpect(jsonPath("$[?(@.code=='AE')].hasStudents", contains(false)))
                // the edit form posts this back for the stale-overwrite check
                .andExpect(jsonPath("$[?(@.code=='AE')].version").exists());
    }
}
