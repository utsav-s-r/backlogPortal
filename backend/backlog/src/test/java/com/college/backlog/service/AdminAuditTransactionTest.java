package com.college.backlog.service;

import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AdminAuditEvent;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.AdminAuditEventRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The one property that makes the P3-9 audit trail worth having: <b>an audit row cannot survive a
 * change that rolled back, and cannot be missing from one that committed.</b>
 *
 * <p>That hinges entirely on {@code AdminAuditService.record} being {@code Propagation.REQUIRED} so
 * it joins the caller's transaction. Switch it to {@code REQUIRES_NEW} and the audit trail starts
 * recording things that never happened — a failure mode no status-code or content assertion
 * anywhere else in the suite can see, because both halves look fine in isolation.
 *
 * <p><b>NOT {@code @Transactional}</b>, deliberately, and for the same reason
 * {@code FetchStatementCountTest} is not: a test-managed transaction would wrap both cases in one
 * rollback and the commit case would prove nothing. Hence real commits plus {@code @AfterEach}
 * cleanup.
 */
@SpringBootTest
@ActiveProfiles("test")
class AdminAuditTransactionTest {

    private static final String ACTOR_NAME = "audit-tx-actor";
    private static final String TARGET = "audit-tx-target";

    @Autowired
    private AdminAuditService auditService;

    @Autowired
    private AdminAuditEventRepository auditRepository;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @AfterEach
    void cleanUp() {
        auditRepository.deleteAll(rowsForThisTest());
    }

    private List<AdminAuditEvent> rowsForThisTest() {
        return auditRepository.findAll().stream()
                .filter(e -> ACTOR_NAME.equals(e.getActor()))
                .toList();
    }

    private static User actor() {
        User u = new User();
        u.setUsername(ACTOR_NAME);
        u.setRole(UserRole.ADMIN);
        return u;
    }

    @Test
    void writesTheRowWithActorActionTargetAndTimestamp() {
        transactionTemplate.executeWithoutResult(status -> auditService.record(
                AdminAuditAction.USER_CREATE, actor(), AuditTargetType.USER, TARGET, "role=HOD"));

        assertThat(rowsForThisTest()).singleElement().satisfies(row -> {
            assertThat(row.getAction()).isEqualTo(AdminAuditAction.USER_CREATE);
            assertThat(row.getActor()).isEqualTo(ACTOR_NAME);
            assertThat(row.getActorRole().name()).isEqualTo("ADMIN");
            assertThat(row.getTargetType()).isEqualTo(AuditTargetType.USER);
            assertThat(row.getTargetId()).isEqualTo(TARGET);
            assertThat(row.getDetail()).isEqualTo("role=HOD");
            assertThat(row.getTimestamp()).isNotNull();
        });
    }

    /**
     * The mutation target. With REQUIRED this passes; with REQUIRES_NEW the audit row commits on its
     * own and survives the rollback, leaving a permanent record of a change that never happened.
     */
    @Test
    void rollsBackTheAuditRowWhenTheSurroundingChangeFails() {
        assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
            auditService.record(AdminAuditAction.USER_DELETE, actor(), AuditTargetType.USER,
                    TARGET, "role=ADMIN");
            throw new IllegalStateException("the change failed after its audit row was written");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(rowsForThisTest())
                .as("an audit row must never outlive the transaction whose change rolled back")
                .isEmpty();
    }

    /** users.role is nullable and its CHECK admits NULL, so the mapping must tolerate it rather
     *  than NPE inside the audit write and take down the operation it was recording. */
    @Test
    void toleratesAnActorWithNoRole() {
        User roleless = new User();
        roleless.setUsername(ACTOR_NAME);

        transactionTemplate.executeWithoutResult(status -> auditService.record(
                AdminAuditAction.USER_PASSWORD_RESET, roleless, AuditTargetType.USER, TARGET, null));

        assertThat(rowsForThisTest()).singleElement()
                .satisfies(row -> assertThat(row.getActorRole()).isNull());
    }
}
