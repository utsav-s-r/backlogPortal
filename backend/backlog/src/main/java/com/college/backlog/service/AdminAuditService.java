package com.college.backlog.service;

import com.college.backlog.model.ActorRole;
import com.college.backlog.model.AdminAuditAction;
import com.college.backlog.model.AdminAuditEvent;
import com.college.backlog.model.AuditTargetType;
import com.college.backlog.model.User;
import com.college.backlog.repository.AdminAuditEventRepository;
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

    @Autowired
    private AdminAuditEventRepository repository;

    /** Identity comes from the resolved caller, never a request body. */
    @Transactional(propagation = Propagation.REQUIRED)
    public void record(AdminAuditAction action, User actor, AuditTargetType targetType,
                       String targetId, String detail) {
        repository.save(new AdminAuditEvent(
                action, actor.getUsername(), toActorRole(actor), targetType, targetId, detail));
    }

    /** users.role is nullable and its CHECK admits NULL, so this cannot assume a value — the
     *  column is nullable to match. Names are identical across the two enums by construction. */
    private static ActorRole toActorRole(User actor) {
        return actor.getRole() == null ? null : ActorRole.valueOf(actor.getRole().name());
    }
}
