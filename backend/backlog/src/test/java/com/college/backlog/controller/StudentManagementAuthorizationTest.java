package com.college.backlog.controller;

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
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role × endpoint matrix for /api/admin/students, executed rather than asserted from
 * annotations. {@code EndpointAuthorizationInventoryTest} owns "a rule exists"; this owns "the rule
 * is the RIGHT one and is actually enforced" — it passes today whether {@code @PreAuthorize} names
 * ADMIN or all five roles, and Cypress can only show a tab is hidden (every spec stubs its API
 * calls, so no spec can ever observe a 403).
 *
 * <p>Three things make this real rather than vacuous:
 * <ol>
 *   <li><b>The FULL context</b> ({@code @SpringBootTest}, not standalone MockMvc).
 *       {@code @EnableMethodSecurity} lives on SecurityConfig, so a standalone setup skips method
 *       security entirely and every 200/403 assertion holds no matter what the annotation says.
 *       This is the one place the repo's usual standalone-MockMvc advice is actively wrong.</li>
 *   <li><b>Exactly 403</b>, never "not 2xx" — see {@link AdminAuthorizationFixture} for the 401 that
 *       would otherwise pass for the wrong reason.</li>
 *   <li><b>CONTENT, not just status, wherever scoping is the point.</b> The failure worth this whole
 *       exercise is a proctor's empty assigned set becoming an unfiltered query: it answers
 *       <b>200 with the whole college</b>, and only a content assertion can see it.</li>
 * </ol>
 *
 * <p><b>Why no 201 on create and no 200 on a real import.</b> {@code StudentManagementService
 * .createStudent} is {@code @Transactional(propagation = REQUIRES_NEW)}, which suspends this test's
 * rollback transaction: a happy-path create would COMMIT rows into the shared throwaway database and
 * outlive the test (and, being suspended, could not see the fixture's uncommitted departments
 * anyway). So the "this role is allowed" side of create/import is proven by reaching a <b>400</b> —
 * past the role and scope checks, stopped by the service's own validation. A 403 there would fail
 * this test; that is exactly the distinction being asserted. Every other write path is plain
 * {@code @Transactional}, joins this transaction, and rolls back.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class StudentManagementAuthorizationTest {

    private static final String STUDENTS = "/api/admin/students";
    private static final String EDIT_BODY =
            "{\"name\":\"Edited Name\",\"currentSemester\":4,\"entrySemester\":1}";
    private static final String DOB_BODY = "{\"dateOfBirth\":\"2006-02-02\"}";
    /** Well-formed everywhere the SCOPE check looks (branch code CS), invalid only later, in the
     *  service — so a 400 proves the caller cleared authorization. A malformed USN would be useless
     *  here: it has no branch code, and a dept-scoped caller is refused 403 for that alone. */
    private static final String CREATE_IN_CS_THAT_FAILS_VALIDATION =
            "{\"rollNo\":\"1MS24CS777\",\"name\":\"New\",\"dateOfBirth\":\"2006-01-01\","
                    + "\"currentSemester\":3,\"entrySemester\":1}"; // 3 is odd — parity refusal
    private static final String CREATE_IN_CV =
            "{\"rollNo\":\"1MS24CV777\",\"name\":\"New\",\"dateOfBirth\":\"2006-01-01\","
                    + "\"currentSemester\":4,\"entrySemester\":1}";

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;

    @BeforeEach
    void seedTheCast() {
        AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
    }

    // ---- GET / (list) — the roster, where scope is content, not status ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminListsEveryDepartment() throws Exception {
        mockMvc.perform(get(STUDENTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].rollNo", hasItem(CV_STUDENT)))
                .andExpect(jsonPath("$.content[*].rollNo", hasItem(CS_ASSIGNED_A)));
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalListsEveryDepartmentToo() throws Exception {
        mockMvc.perform(get(STUDENTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].rollNo", hasItem(CV_STUDENT)));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodSeesOwnDepartmentOnly() throws Exception {
        // 200 is not the assertion — the absence of the Civil student is
        mockMvc.perform(get(STUDENTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].rollNo", hasItem(CS_UNASSIGNED)))
                .andExpect(jsonPath("$.content[*].rollNo", not(hasItem(CV_STUDENT))));
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeSeesOwnDepartmentOnly() throws Exception {
        mockMvc.perform(get(STUDENTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].rollNo", hasItem(CS_UNASSIGNED)))
                .andExpect(jsonPath("$.content[*].rollNo", not(hasItem(CV_STUDENT))));
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorSeesOnlyAssignedStudents() throws Exception {
        // CS_UNASSIGNED is in the proctor's own DEPARTMENT: it is excluded by the assignment scope
        // alone, so its absence is what separates the two layers. Exact set, not "contains".
        mockMvc.perform(get(STUDENTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].rollNo",
                        containsInAnyOrder(CS_ASSIGNED_A, CS_ASSIGNED_B)));
    }

    @Test
    @WithMockUser(username = PROCTOR_WITHOUT_STUDENTS, roles = "PROCTOR")
    void proctorWithNoAssignmentsSeesAnEmptyPageNotTheWholeCollege() throws Exception {
        // THE case this suite exists for. assignedRollNos returns null for a non-proctor
        // (unrestricted) and a Set for a proctor, so coercing this empty Set to null — or dropping
        // the empty-set short-circuit, since an empty SQL IN list is invalid and tempts exactly that
        // "fix" — yields 200 with every student in it. The status alone would still be 200.
        mockMvc.perform(get(STUDENTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(0)))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        // 401, not 403: there is no identity to deny
        mockMvc.perform(get(STUDENTS)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "1MS24CS001", roles = "STUDENT")
    void aStudentTokenCannotReachTheAdminSurface() throws Exception {
        // pins the SecurityConfig URL rule for /api/admin/**, one layer above @PreAuthorize
        mockMvc.perform(get(STUDENTS)).andExpect(status().isForbidden());
    }

    // ---- POST / (create) — proctors never, dept roles inside their own department ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotCreateAccounts() throws Exception {
        mockMvc.perform(post(STUDENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_IN_CS_THAT_FAILS_VALIDATION))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotCreateIntoAnotherDepartment() throws Exception {
        mockMvc.perform(post(STUDENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_IN_CV))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodClearsAuthorizationForOwnDepartmentAndHitsValidation() throws Exception {
        // 400, not 403: past the role check and past the dept scope check. See the class javadoc for
        // why the allowed side stops here instead of asserting 201.
        mockMvc.perform(post(STUDENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_IN_CS_THAT_FAILS_VALIDATION))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminClearsAuthorizationInAnyDepartment() throws Exception {
        mockMvc.perform(post(STUDENTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(CREATE_IN_CS_THAT_FAILS_VALIDATION))
                .andExpect(status().isBadRequest());
    }

    // ---- PUT /{rollNo} (edit) — the per-student hard scope ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorEditsAnAssignedStudent() throws Exception {
        mockMvc.perform(put(STUDENTS + "/" + CS_ASSIGNED_A).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotEditAnUnassignedStudentInOwnDepartment() throws Exception {
        // dept scope passes here; assertSupervises is the only thing refusing
        mockMvc.perform(put(STUDENTS + "/" + CS_UNASSIGNED).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotEditAcrossDepartments() throws Exception {
        mockMvc.perform(put(STUDENTS + "/" + CV_STUDENT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodEditsAnyStudentInOwnDepartment() throws Exception {
        // unassigned to any proctor, and still editable: the hard scope is the PROCTOR's, not HOD's
        mockMvc.perform(put(STUDENTS + "/" + CS_UNASSIGNED).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotEditAcrossDepartments() throws Exception {
        mockMvc.perform(put(STUDENTS + "/" + CV_STUDENT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotEditAcrossDepartments() throws Exception {
        mockMvc.perform(put(STUDENTS + "/" + CV_STUDENT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminEditsAcrossDepartments() throws Exception {
        mockMvc.perform(put(STUDENTS + "/" + CV_STUDENT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(EDIT_BODY))
                .andExpect(status().isOk());
    }

    // ---- POST /{rollNo}/reset-dob — same scope as edit; it rewrites a LOGIN CREDENTIAL ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorResetsDobForAnAssignedStudent() throws Exception {
        mockMvc.perform(post(STUDENTS + "/" + CS_ASSIGNED_B + "/reset-dob").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DOB_BODY))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotResetDobForAnUnassignedStudent() throws Exception {
        // a leak here hands over another student's login credential, not just a data edit
        mockMvc.perform(post(STUDENTS + "/" + CS_UNASSIGNED + "/reset-dob").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DOB_BODY))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeResetsDobInOwnDepartmentOnly() throws Exception {
        mockMvc.perform(post(STUDENTS + "/" + CS_ASSIGNED_A + "/reset-dob").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DOB_BODY))
                .andExpect(status().isNoContent());
        mockMvc.perform(post(STUDENTS + "/" + CV_STUDENT + "/reset-dob").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(DOB_BODY))
                .andExpect(status().isForbidden());
    }

    // ---- DELETE /{rollNo} — refused to proctors even for their OWN students ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotDeleteEvenAnAssignedStudent() throws Exception {
        // rejectProctor runs BEFORE the supervision check on purpose: a proctor "removes a student"
        // by unassigning, never by deleting the account. Supervision must not unlock delete.
        mockMvc.perform(delete(STUDENTS + "/" + CS_ASSIGNED_A).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotDeleteAcrossDepartments() throws Exception {
        mockMvc.perform(delete(STUDENTS + "/" + CV_STUDENT).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodDeletesAnUnreferencedStudentInOwnDepartment() throws Exception {
        // deleteStudent is plain @Transactional, so this joins the test transaction and rolls back
        mockMvc.perform(delete(STUDENTS + "/" + CS_UNASSIGNED).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---- POST /import — refused to proctors; dept scope enforced PER ROW ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorCannotImport() throws Exception {
        mockMvc.perform(post(STUDENTS + "/import").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(dryRunOf(CS_NEW, CV_NEW)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodImportRefusesTheRowOutsideItsDepartment() throws Exception {
        // The request is 200 — per-row failures are BatchResult rows, not a request-level status —
        // so the scope check is only observable in the body. A widened scope reads as 200 either way.
        mockMvc.perform(post(STUDENTS + "/import").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(dryRunOf(CS_NEW, CV_NEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results[0].status").value("WOULD_CREATE"))
                .andExpect(jsonPath("$.results[1].status").value("ERROR"))
                .andExpect(jsonPath("$.errors").value(1));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminImportAcceptsBothDepartments() throws Exception {
        // the contrast that proves the HOD case above is scoping and not some unrelated row failure
        mockMvc.perform(post(STUDENTS + "/import").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(dryRunOf(CS_NEW, CV_NEW)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.errors").value(0))
                .andExpect(jsonPath("$.created").value(2));
    }

    private static final String CS_NEW = "1MS24CS900";
    private static final String CV_NEW = "1MS24CV900";

    /** Dry run only: a real import would commit through createStudent's REQUIRES_NEW transaction. */
    private static String dryRunOf(String... rollNos) {
        StringBuilder rows = new StringBuilder();
        for (String rollNo : rollNos) {
            if (rows.length() > 0) rows.append(',');
            rows.append("{\"rollNo\":\"").append(rollNo)
                .append("\",\"name\":\"Imported\",\"dateOfBirth\":\"2006-01-01\"}");
        }
        return "{\"dryRun\":true,\"defaultCurrentSemester\":4,\"defaultEntrySemester\":1,\"rows\":["
                + rows + "]}";
    }
}
