package com.college.backlog.service;

import com.college.backlog.controller.AdminAuthorizationFixture;
import com.college.backlog.model.ExamCycle;
import com.college.backlog.model.Registration;
import com.college.backlog.model.RegistrationStatus;
import com.college.backlog.model.Student;
import com.college.backlog.model.Subject;
import com.college.backlog.repository.DepartmentRepository;
import com.college.backlog.repository.ExamCycleRepository;
import com.college.backlog.repository.ProctorAssignmentRepository;
import com.college.backlog.repository.RegistrationRepository;
import com.college.backlog.repository.StudentRepository;
import com.college.backlog.repository.SubjectRepository;
import com.college.backlog.repository.UserRepository;
import jakarta.persistence.EntityManagerFactory;
import org.hibernate.LazyInitializationException;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static com.college.backlog.controller.AdminAuthorizationFixture.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/**
 * §2.4 — the persistence layer's fetch behaviour, asserted in SQL statement counts rather than
 * verified by hand. docs/adr/persistence-fetching.md says this area "needs a running app curled as
 * a dept-scoped role" and that <b>nothing in CI can catch a regression here</b>: the backend suite
 * only boots the context, and every Cypress spec {@code cy.intercept}s its API calls, so an N+1 or
 * a broken {@code @EntityGraph} ships with the whole suite green. This automates that check.
 *
 * <p><b>This class is deliberately NOT {@code @Transactional} — that is the whole design.</b> Every
 * authorization suite here is transactional and rolls back, but a test-managed transaction would
 * hold ONE persistence context open across the service call, which is precisely
 * {@code open-in-view=true} rebuilt by hand — the setting this application turns off. Under it:
 * {@code findByRegId}'s entity graph could be deleted and the lazy walk would still succeed,
 * {@code listSummaries}' own {@code @Transactional} would degrade to a no-op join, and the
 * first-level cache would swallow the very statements being counted. Every assertion below would
 * pass while proving nothing. The price is that fixtures are committed and cleaned by hand in
 * {@link #cleanTheDatabase()}.
 *
 * <p><b>Counts are asserted as a PROPERTY, not a constant.</b> The N+1 question is not "is it 4
 * statements" but "does it grow with the row count", so the list is measured at 3 rows and again at
 * {@link #MANY} and the two must be EQUAL. A magic constant would need editing every time the
 * fixture changes, and would get "fixed" by bumping the number — which is how a real N+1 gets
 * waved through. An upper bound is asserted too, to catch a wholesale regression.
 *
 * <p>Needs the local Postgres — see BacklogApplicationTests for the docker line.
 */
@SpringBootTest
@ActiveProfiles("test")
class FetchStatementCountTest {

    /** Enough rows that a per-row query is unmistakable, and above the number of distinct subjects
     *  so {@code @BatchSize} still collapses them into one statement. */
    private static final int MANY = 12;
    private static final int FEW = 3;

    /** Rows for the page-size case below: must exceed {@link #PAGE}'s size, and exceed any batch
     *  size a regression would plausibly land on — 30 (the historical value) and 50 (what
     *  harmonising with {@code Subject.eligibleDepartments} would give). Deliberately far below
     *  MAX_PAGE_SIZE: statement count follows rows RETURNED, not the size requested, so 60 catches
     *  the same regressions 200 would at a fraction of the fixture cost. Don't trim it to ~40 to
     *  save inserts — that stops catching 50. */
    private static final int ACROSS_BATCH = 60;

    /** The admin list's UI default. The page-size case needs this to stay below
     *  {@link #ACROSS_BATCH}; nothing else here depends on the exact value. */
    private static final Pageable PAGE = PageRequest.of(0, 25);

    @Autowired private UserRepository userRepository;
    @Autowired private DepartmentRepository departmentRepository;
    @Autowired private StudentRepository studentRepository;
    @Autowired private ProctorAssignmentRepository assignmentRepository;
    @Autowired private SubjectRepository subjectRepository;
    @Autowired private ExamCycleRepository examCycleRepository;
    @Autowired private RegistrationRepository registrationRepository;
    @Autowired private RegistrationService registrationService;
    @Autowired private EntityManagerFactory entityManagerFactory;

    private Statistics statistics;
    private AdminAuthorizationFixture.Ids ids;
    private ExamCycle cycle;

    @BeforeEach
    void seedCommittedFixtures() {
        // defensive: this class commits, so a previous failure could leave rows behind
        cleanTheDatabase();
        ids = AdminAuthorizationFixture.seed(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository);
        cycle = examCycleRepository.save(new ExamCycle("Fetch Fixture", "2026-01"));

        statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        // enabled at runtime rather than via a property, so no other test pays for the collection
        statistics.setStatisticsEnabled(true);
    }

    @AfterEach
    void cleanTheDatabase() {
        AdminAuthorizationFixture.cleanup(userRepository, departmentRepository, studentRepository,
                assignmentRepository, subjectRepository, registrationRepository, examCycleRepository);
        if (statistics != null) {
            statistics.setStatisticsEnabled(false);
        }
    }

    // ---- the N+1 property ----

    @Test
    void theAdminListCostsTheSameNumberOfStatementsAtThreeRowsAsAtTwelve() {
        seedRegistrations(FEW);
        long few = statementsFor(() -> registrationService.listSummaries(anyRegistration(), PAGE));

        seedRegistrations(MANY - FEW); // top up to MANY
        long many = statementsFor(() -> registrationService.listSummaries(anyRegistration(), PAGE));

        // The list leaves `subjects` lazy on purpose (fetching a collection on a paginated query
        // paginates in memory — see the next test), so every row's subjects would be its own SELECT
        // without @BatchSize(30) on the mapping. Flatness IS the assertion; the absolute number is
        // an implementation detail that may legitimately shift.
        assertThat(many)
                .as("statement count must not grow with the number of registrations (N+1)")
                .isEqualTo(few);
        assertThat(few)
                .as("a small bounded number of statements: count, rows, subjects batch, eligible-depts batch")
                .isLessThanOrEqualTo(6);
    }

    @Test
    void theAdminListCostsTheSameNumberOfStatementsAtAFullPageAsAtTheDefaultPage() {
        seedRegistrations(ACROSS_BATCH);

        long defaultPage = statementsFor(() -> registrationService.listSummaries(anyRegistration(), PAGE));
        long fullPage = statementsFor(() ->
                registrationService.listSummaries(anyRegistration(), PageRequest.of(0, ACROSS_BATCH)));

        // A DIFFERENT axis from the test above, which varies rows at a FIXED page size and so cannot
        // see this: @BatchSize batches the lazy `subjects` loads one page-row at a time, so a batch
        // size below the page issues ceil(rows / batchSize) statements — a staircase in page size.
        // It was real. The rule is that @BatchSize EXCEEDS the page, not that it equals any
        // constant; see Registration.subjects and docs/adr/persistence-fetching.md.
        //
        // Bounds what it proves: this fails on any batch size under ACROSS_BATCH — the shape a
        // revert or a tidy-up produces. It cannot see a MAX_PAGE_SIZE raised past the batch size,
        // which is what the headroom on that annotation is for.
        assertThat(fullPage)
                .as("statement count must not grow with PAGE SIZE: @BatchSize must cover a full page")
                .isEqualTo(defaultPage);
    }

    @Test
    void theAdminListHydratesOnlyOnePageOfRegistrationsNotTheWholeTable() {
        seedRegistrations(MANY);
        int pageSize = 5;
        statistics.clear();

        Page<?> page = registrationService.listSummaries(anyRegistration(), PageRequest.of(0, pageSize));

        assertThat(page.getContent()).hasSize(pageSize);
        assertThat(page.getTotalElements()).isEqualTo(MANY);
        // Trap 1 in the ADR: adding a collection to the paginated @EntityGraph makes Hibernate drop
        // the SQL LIMIT and paginate IN MEMORY (HHH000104) — the content and the totals stay
        // correct, so only the number of entities actually hydrated shows it. That turns a latency
        // problem into an unbounded-memory one at MAX_PAGE_SIZE.
        long registrationsLoaded = statistics
                .getEntityStatistics(Registration.class.getName()).getLoadCount();
        assertThat(registrationsLoaded)
                .as("only the requested page may be hydrated; more means in-memory pagination")
                .isLessThanOrEqualTo(pageSize);
    }

    // ---- the entity graphs ----

    @Test
    void findByRegIdIsFullyUsableOutsideAnyTransaction() {
        seedRegistrations(1);
        String regId = registrationRepository.findAll().get(0).getRegId();

        Registration reg = registrationRepository.findByRegId(regId).orElseThrow();

        // Exactly the walk the dept/proctor scope check performs, in a controller, outside any
        // transaction. It regressed once: the graph defaulted to type = FETCH, which demoted the
        // EAGER Subject.eligibleDepartments to lazy, so a scope DENIAL threw
        // LazyInitializationException and answered 500 instead of 403 — on the failure path only,
        // where nothing looks. type = LOAD is what keeps this working.
        assertThatCode(() -> {
            for (Subject s : reg.getSubjects()) {
                s.getEligibleDepartments().size();
            }
            reg.getStudent().getRollNo();
            reg.getExamCycle().getName();
        }).doesNotThrowAnyException();
        assertThat(reg.getSubjects()).isNotEmpty();
    }

    @Test
    void thePaginatedQueryLeavesSubjectsLazyWhichIsWhyTheMappingMustStayInTheService() {
        seedRegistrations(1);
        statistics.clear();

        // the repository call WITHOUT the service's transaction around the mapping
        Registration reg = registrationRepository.findAll(anyRegistration(), PAGE).getContent().get(0);

        // Not a defect — the deliberate consequence of graphing only the to-one sides here. It is
        // what makes RegistrationService.listSummaries' @Transactional load-bearing rather than
        // decorative, and it fails silently if someone later calls listSummaries from INSIDE
        // RegistrationService (the Spring proxy is bypassed, so no transaction starts).
        assertThatExceptionOfType(LazyInitializationException.class)
                .isThrownBy(() -> reg.getSubjects().size());
    }

    @Test
    void theServiceMappingSucceedsWhereTheBareRepositoryCallCannot() {
        seedRegistrations(FEW);

        // same query, same lazy association — the only difference is the service's transaction
        Page<?> summaries = registrationService.listSummaries(anyRegistration(), PAGE);

        assertThat(summaries.getContent()).hasSize(FEW);
    }

    // ---- helpers ----

    private org.springframework.data.jpa.domain.Specification<Registration> anyRegistration() {
        return RegistrationSpecification.builder().build();
    }

    /** Runs the call with a cleared statistics counter and returns the JDBC statements it prepared. */
    private long statementsFor(Runnable call) {
        statistics.clear();
        call.run();
        return statistics.getPrepareStatementCount();
    }

    /**
     * Registrations spread over both fixture students and both subjects, so the batch fetch has
     * something to collapse and the count is not accidentally flat.
     */
    private void seedRegistrations(int count) {
        List<Student> students = studentRepository.findAll();
        Subject csSubject = subjectRepository.findById(ids.csSubjectId).orElseThrow();
        Subject cvSubject = subjectRepository.findById(ids.cvSubjectId).orElseThrow();
        long existing = registrationRepository.count();
        for (int i = 0; i < count; i++) {
            Student student = students.get(i % students.size());
            Registration r = new Registration();
            r.setRegId("REG-FETCH-" + (existing + i));
            r.setStudent(student);
            r.setSubjects(i % 2 == 0 ? List.of(csSubject) : List.of(csSubject, cvSubject));
            r.setExamCycle(cycle);
            // VERIFIED, not SUBMITTED: uq_pending_reg_per_cycle is a partial unique index on
            // (roll_no, exam_cycle_id) WHERE status = 'SUBMITTED', so several pending rows for one
            // student in one cycle are not storable — and this fixture reuses students on purpose.
            r.setStatus(RegistrationStatus.VERIFIED);
            r.setRegisteredAt(LocalDateTime.now());
            r.setSnapName(student.getName());
            r.setSnapEmail(student.getEmail());
            r.setSnapBranch(student.getBranch());
            r.setSnapSemester(student.getCurrentSemester());
            r.setSnapYearOfJoining(student.getYearOfJoining());
            registrationRepository.save(r);
        }
    }
}
