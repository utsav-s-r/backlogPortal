package com.college.backlog.service;

import com.college.backlog.model.ActorRole;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AdminAuditEvent;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.repository.AdminAuditEventRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The ONE writer of {@code admin_audit_events} (P3-9). Keep it that way: a second writer is how the
 * two sides of an audit trail start disagreeing.
 *
 * <p><b>Propagation is REQUIRED, deliberately, and must stay that way.</b> The audit row has to
 * share the caller's transaction so a failure rolls back BOTH — the change must never commit
 * without its record. REQUIRES_NEW would commit the audit row independently and, worse, would
 * happily record a change that then rolled back. Callers are therefore {@code @Transactional}
 * themselves; where they are not, there is nothing to join and the insert stands alone, which is
 * the same durability the change itself has.
 */
@Service
public class AdminAuditService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuditService.class);

    /** Shown to the admin when {@link #recordBestEffort} could not write. Their action SUCCEEDED;
     *  what is missing is the record of it, and saying so is the whole point. */
    public static final String UNRECORDED_WARNING =
        "This completed, but it could not be written to the audit log. "
            + "The changes above are saved — tell an administrator so the record can be corrected.";

    @Autowired
    private AdminAuditEventRepository repository;

    /** Identity comes from the resolved caller, never a request body. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AdminAuditAction action, User actor, AuditTargetType targetType,
                       String targetId, String detail) {
        repository.save(new AdminAuditEvent(
                action, actor.getUsername(), toActorRole(actor), targetType, targetId, detail));
    }

    /**
     * For a caller with NO transaction to join, where the change is already committed by the time
     * the audit row is written — the two batch loops (subject clone, subject import), which commit
     * each row on its own so one bad row cannot poison the batch.
     *
     * <p>One audit row can never be atomic with N independent commits, so the only question is
     * what happens when that last insert loses. It used to escape and 500 the request: the admin
     * was told the whole operation failed when every row had landed, and lost the per-row result
     * table, which exists nowhere else. The audit row was missing EITHER WAY — the old behaviour
     * paid that price and threw away the report as well.
     *
     * <p>So: keep the result, put the outcome in the log where it is still durable, and hand the
     * caller a warning to show. The gap is disclosed, never silent.
     *
     * <p><b>Never call this from a {@code @Transactional} method — use {@link #record}.</b> There
     * the failed insert has already marked the transaction rollback-only, so swallowing it would
     * report success for a write that is about to be undone. This was an
     * {@code isActualTransactionActive()} guard that threw; it had to go, because
     * {@code @SpringBootTest @Transactional} — the project's standard controller harness, and the
     * only one that can exercise these endpoints without leaking committed rows into the shared
     * test database — wraps every request in exactly such a transaction. The check could not tell
     * the harness from real misuse, which makes it the wrong check rather than a strict one.
     *
     * @return null when recorded; {@link #UNRECORDED_WARNING} when it could not be
     */
    public String recordBestEffort(AdminAuditAction action, User actor, AuditTargetType targetType,
                                   String targetId, String detail) {
        try {
            record(action, actor, targetType, targetId, detail);
            return null;
        } catch (RuntimeException e) {
            // The outcome, not just the failure: the catch-all handler would have logged the
            // connection error alone, and what actually happened to the rows is only in `detail`.
            log.error("AUDIT_WRITE_FAILED action={} actor={} target={}:{} detail={}",
                    action, actor.getUsername(), targetType, targetId, detail, e);
            return UNRECORDED_WARNING;
        }
    }

    /** users.role is nullable and its CHECK admits NULL, so this cannot assume a value — the
     *  column is nullable to match. Names are identical across the two enums by construction. */
    private static ActorRole toActorRole(User actor) {
        return actor.getRole() == null ? null : ActorRole.valueOf(actor.getRole().name());
    }
}
