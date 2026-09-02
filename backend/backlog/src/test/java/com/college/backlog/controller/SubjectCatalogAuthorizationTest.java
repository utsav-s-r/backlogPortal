package com.college.backlog.controller;

import com.college.backlog.repository.AdminAuditEventRepository;
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
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The role × endpoint matrix for the subject catalog — step 3 of
 * claude-work/notes/role-endpoint-matrix-plan.md. Two controllers, one resource: create / list /
 * edit / delete / CSV import on {@link SubjectController}, and year-to-year cloning on
 * {@link SubjectCloneController}. Create's dept-scope guard is inline in the handler and does NOT
 * live in {@code SubjectService.createSubject}, which takes no caller department at all, so the
 * write that introduces rows would be unproven without the cases here.
 *
 * <p>Simpler than steps 1-2: no proctor axis, and dept scoping is by department ID rather than by
 * USN branch code. Two things are still worth stating:
 * <ol>
 *   <li><b>PROCTOR is absent from both {@code @PreAuthorize} lists on purpose</b>, and — as with
 *       DEPT_OFFICE on the proctor controller — that annotation is the ONLY layer denying them:
 *       SecurityConfig's {@code /api/admin/**} rule admits PROCTOR. Nothing below would stop a
 *       widened annotation, and {@code EndpointAuthorizationInventoryTest} would still pass.</li>
 *   <li><b>A dept-scoped caller's requested {@code deptId} must be IGNORED, not honoured.</b> The
 *       list endpoint pins HOD/DEPT_OFFICE to their own department and silently drops the parameter;
 *       the clone endpoints 403 a mismatch instead. Both are correct, and both are content- or
 *       status-observable only if the request actually asks for the other department — so these
 *       tests pass {@code deptId} explicitly rather than omitting it.</li>
 * </ol>
 *
 * <p>Every service on these paths is plain {@code @Transactional} (or not transactional at all), so
 * unlike the student controller the happy paths can assert their real 200/201 and still roll back.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class SubjectCatalogAuthorizationTest {

    private static final String SUBJECTS = "/api/admin/subjects";
    private static final String CLONE_PREVIEW = "/api/admin/subjects/clone/preview";
    private static final String CLONE_APPLY = "/api/admin/subjects/clone/apply";
    private static final String IMPORT = "/api/admin/subjects/import";
    private static final int CLONE_TARGET_YEAR = 2025;

    @Autowired private MockMvc mockMvc;
    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private AdminAuditEventRepository auditEventRepository;

    private AdminAuthorizationFixture.Ids ids;

    @BeforeEach
    void seedTheCast() {
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
    }

    // ---- PROCTOR: denied on the whole catalog surface ----

    @Test
    @WithMockUser(username = PROCTOR, roles = "PROCTOR")
    void proctorIsDeniedTheEntireCatalogSurface() throws Exception {
        mockMvc.perform(get(SUBJECTS)).andExpect(status().isForbidden());
        mockMvc.perform(post(SUBJECTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(ids.csDeptId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(put(SUBJECTS + "/" + ids.csSubjectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(CS_SUBJECT_CODE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete(SUBJECTS + "/" + ids.csSubjectId).with(csrf()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CLONE_PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody(ids.csDeptId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CLONE_APPLY).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(ids.csDeptId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(ids.csDeptId, false)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anonymousIsRejectedWith401() throws Exception {
        mockMvc.perform(get(SUBJECTS)).andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = CS_ASSIGNED_A, roles = "STUDENT")
    void aStudentTokenCannotReachTheCatalog() throws Exception {
        mockMvc.perform(get(SUBJECTS)).andExpect(status().isForbidden());
    }

    // ---- GET / (list) — dept scope is content ----

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminListsEveryDepartment() throws Exception {
        mockMvc.perform(get(SUBJECTS).param("size", "200"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].courseCode", hasItem(CS_SUBJECT_CODE)))
                .andExpect(jsonPath("$.content[*].courseCode", hasItem(CV_SUBJECT_CODE)));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodAskingForAnotherDepartmentStillGetsOnlyItsOwn() throws Exception {
        // the deptId parameter is IGNORED for a dept-scoped caller, not honoured and not refused —
        // so the only observable difference is the absence of the other department's subject
        mockMvc.perform(get(SUBJECTS).param("size", "200").param("deptId", String.valueOf(ids.cvDeptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].courseCode", hasItem(CS_SUBJECT_CODE)))
                .andExpect(jsonPath("$.content[*].courseCode", not(hasItem(CV_SUBJECT_CODE))));
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeAskingForAnotherDepartmentStillGetsOnlyItsOwn() throws Exception {
        mockMvc.perform(get(SUBJECTS).param("size", "200").param("deptId", String.valueOf(ids.cvDeptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].courseCode", not(hasItem(CV_SUBJECT_CODE))));
    }

    // ---- POST /api/admin/subjects (create) ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCreatesIntoOwnDepartment() throws Exception {
        mockMvc.perform(post(SUBJECTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(ids.csDeptId)))
                .andExpect(status().isCreated());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotCreateIntoAnotherDepartment() throws Exception {
        mockMvc.perform(post(SUBJECTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(ids.cvDeptId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminCreatesIntoAnyDepartment() throws Exception {
        mockMvc.perform(post(SUBJECTS).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(createBody(ids.cvDeptId)))
                .andExpect(status().isCreated());
    }

    // ---- PUT /{id} (edit) ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodEditsOwnDepartmentsSubject() throws Exception {
        mockMvc.perform(put(SUBJECTS + "/" + ids.csSubjectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(CS_SUBJECT_CODE)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotEditAnotherDepartmentsSubject() throws Exception {
        mockMvc.perform(put(SUBJECTS + "/" + ids.cvSubjectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(CV_SUBJECT_CODE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotEditAnotherDepartmentsSubject() throws Exception {
        mockMvc.perform(put(SUBJECTS + "/" + ids.cvSubjectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(CV_SUBJECT_CODE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminEditsAnyDepartmentsSubject() throws Exception {
        mockMvc.perform(put(SUBJECTS + "/" + ids.cvSubjectId).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(CV_SUBJECT_CODE)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void editingAnUnknownSubjectIsA404NotA403() throws Exception {
        // id in the PATH -> 404, and it proves ADMIN cleared the role check
        mockMvc.perform(put(SUBJECTS + "/999999").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(editBody(CS_SUBJECT_CODE)))
                .andExpect(status().isNotFound());
    }

    // ---- DELETE /{id} ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodDeletesOwnDepartmentsUnreferencedSubject() throws Exception {
        mockMvc.perform(delete(SUBJECTS + "/" + ids.csSubjectId).with(csrf()))
                .andExpect(status().isNoContent());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotDeleteAnotherDepartmentsSubject() throws Exception {
        mockMvc.perform(delete(SUBJECTS + "/" + ids.cvSubjectId).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotDeleteAnotherDepartmentsSubject() throws Exception {
        mockMvc.perform(delete(SUBJECTS + "/" + ids.cvSubjectId).with(csrf()))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminDeletesAnyDepartmentsSubject() throws Exception {
        mockMvc.perform(delete(SUBJECTS + "/" + ids.cvSubjectId).with(csrf()))
                .andExpect(status().isNoContent());
    }

    // ---- clone: preview + apply ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodPreviewsOwnDepartment() throws Exception {
        mockMvc.perform(post(CLONE_PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody(ids.csDeptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deptId").value(ids.csDeptId));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotPreviewOrApplyForAnotherDepartment() throws Exception {
        // here a mismatched deptId is REFUSED rather than ignored — the opposite of the list
        // endpoint, and deliberately so: cloning writes rows
        mockMvc.perform(post(CLONE_PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody(ids.cvDeptId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(CLONE_APPLY).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(ids.cvDeptId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotApplyForAnotherDepartment() throws Exception {
        mockMvc.perform(post(CLONE_APPLY).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(ids.cvDeptId)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodAppliesIntoOwnDepartment() throws Exception {
        mockMvc.perform(post(CLONE_APPLY).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(applyBody(ids.csDeptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminMustNameADepartmentAndIsNotDeniedForOmittingOne() throws Exception {
        // 400, not 403: ADMIN has no own department to fall back to, so the target is required.
        // The dept-scoped roles get the opposite treatment — their own department is implied.
        mockMvc.perform(post(CLONE_PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceYear\":" + SUBJECT_YEAR + ",\"targetYear\":"
                                + CLONE_TARGET_YEAR + "}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminPreviewsAnyDepartment() throws Exception {
        mockMvc.perform(post(CLONE_PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody(ids.cvDeptId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deptId").value(ids.cvDeptId));
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalPreviewsAnyDepartment() throws Exception {
        mockMvc.perform(post(CLONE_PREVIEW).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(previewBody(ids.cvDeptId)))
                .andExpect(status().isOk());
    }

    // ---- POST /import ----

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodImportsIntoOwnDepartmentWithoutNamingIt() throws Exception {
        // deptId omitted: a dept-scoped caller's own department is implied, as on clone
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(null, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    @WithMockUser(username = HOD, roles = "HOD")
    void hodCannotImportIntoAnotherDepartment() throws Exception {
        // THE case this endpoint's scoping exists for. A silent redirect to the caller's own
        // department would answer 200 and report rows created somewhere the admin did not choose,
        // so the refusal must be explicit.
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(ids.cvDeptId, true)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = DEPT_OFFICE, roles = "DEPT_OFFICE")
    void deptOfficeCannotImportIntoAnotherDepartment() throws Exception {
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(ids.cvDeptId, true)))
                .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminImportsIntoAnyDepartment() throws Exception {
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(ids.cvDeptId, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));
    }

    @Test
    @WithMockUser(username = PRINCIPAL, roles = "PRINCIPAL")
    void principalImportsIntoAnyDepartment() throws Exception {
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(ids.cvDeptId, true)))
                .andExpect(status().isOk());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void adminMustNameADepartmentToImportInto() throws Exception {
        // 400, not 403: ADMIN has no own department to imply, so the target is required — the
        // mirror image of the dept-scoped case above
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(null, true)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void importingIntoAnUnknownDepartmentIsA400NotA404() throws Exception {
        // the id comes from the BODY, so the URL's resource exists and the reference is what's wrong
        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(importBody(999999L, true)))
                .andExpect(status().isBadRequest());
    }

    /**
     * The one case that actually WRITES through import. Every other allowed import case above is a
     * dry run — correct for an authorization assertion, but it means the seam between the row loop
     * and {@code SubjectService.createSubject} would otherwise never execute, and the audit row
     * would never be written by any test.
     *
     * <p>Safe to commit-and-roll-back here: nothing on this path is {@code REQUIRES_NEW}
     * ({@code createSubject} is plain {@code @Transactional} and {@code AdminAuditService.record}
     * is explicitly {@code REQUIRED}), so both joins the test transaction. Check that again before
     * copying this shape onto the student endpoints, where {@code createStudent} IS
     * {@code REQUIRES_NEW} and a happy path would leak committed rows into the shared database.
     */
    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aRealImportPersistsTheSubjectAndItsAuditRow() throws Exception {
        long auditRowsBefore = auditEventRepository.count();

        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(ids.csDeptId, false)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.created").value(1))
                .andExpect(jsonPath("$.results[0].status").value("CREATED"));

        // the row actually landed, under the BATCH year rather than anything from the row
        assertThat(subjectRepository.existsByCourseCodeAndAcademicYearOffered("IMP01", CLONE_TARGET_YEAR))
            .isTrue();
        // and the operation recorded itself — one row for the whole import, as with SUBJECT_CLONE
        assertThat(auditEventRepository.count()).isEqualTo(auditRowsBefore + 1);
    }

    @Test
    @WithMockUser(username = ADMIN, roles = "ADMIN")
    void aDryRunWritesNeitherTheSubjectNorAnAuditRow() throws Exception {
        long auditRowsBefore = auditEventRepository.count();

        mockMvc.perform(post(IMPORT).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(importBody(ids.csDeptId, true)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(1));

        // "created: 1" on a dry run means WOULD create — nothing may actually exist
        assertThat(subjectRepository.existsByCourseCodeAndAcademicYearOffered("IMP01", CLONE_TARGET_YEAR))
            .isFalse();
        assertThat(auditEventRepository.count()).isEqualTo(auditRowsBefore);
    }

    // ---- bodies ----

    /** A new subject in the given department. The course code is free text and unrelated to the
     *  year, so only {@code academicYearOffered} has to stay in the valid range — a 400 here would
     *  mask the 403 these tests are looking for. */
    private static String createBody(Long deptId) {
        return "{\"subjectName\":\"New Subject\",\"courseCode\":\"CS99\",\"semester\":4,"
                + "\"credits\":3,\"academicYearOffered\":" + SUBJECT_YEAR + ",\"deptId\":" + deptId + "}";
    }

    /** The course code is free text, so any value reaches the authorization check unimpeded. */
    private static String editBody(String courseCode) {
        return "{\"subjectName\":\"Renamed\",\"courseCode\":\"" + courseCode + "\",\"semester\":4,"
                + "\"credits\":3}";
    }

    /**
     * A one-row import. {@code dryRun} keeps the assertion about AUTHORIZATION rather than about
     * whether the row happens to be creatable — the scope check runs before any row is touched, so
     * a dry run reaches it just the same. A null deptId omits the field entirely, which is what
     * distinguishes "my own department is implied" from "I named another one".
     */
    private static String importBody(Long deptId, boolean dryRun) {
        return "{" + (deptId == null ? "" : "\"deptId\":" + deptId + ",")
                + "\"academicYearOffered\":" + CLONE_TARGET_YEAR
                + ",\"dryRun\":" + dryRun
                + ",\"rows\":[{\"courseCode\":\"IMP01\",\"subjectName\":\"Imported\","
                + "\"semester\":4,\"credits\":3,\"subjectType\":\"REGULAR\",\"eligibleDeptCodes\":[]}]}";
    }

    private static String previewBody(Long deptId) {
        return "{\"deptId\":" + deptId + ",\"sourceYear\":" + SUBJECT_YEAR + ",\"targetYear\":"
                + CLONE_TARGET_YEAR + "}";
    }

    private static String applyBody(Long deptId) {
        return "{\"deptId\":" + deptId + ",\"targetYear\":" + CLONE_TARGET_YEAR + ",\"rows\":["
                + "{\"subjectName\":\"Cloned\",\"courseCode\":\"CS44\",\"semester\":4,\"credits\":4}]}";
    }
}
