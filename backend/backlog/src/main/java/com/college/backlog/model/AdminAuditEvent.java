package com.college.backlog.model;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Append-only audit record of a privileged staff action; rows are never updated or deleted.
 *
 * <p>Sibling of {@link RegistrationEvent}, which audits what is done TO a student. This one audits
 * what a staff member does — granting a role, opening registration college-wide, exporting personal
 * data. Before P3-9 none of that was recorded anywhere: no audit row, no log line, and no access
 * log to fall back on.
 *
 * <p>No foreign keys, deliberately: an audit row must outlive its subject, and a USER_DELETE whose
 * FK cascaded away would defeat the point.
 */
@Entity
@Table(name = "admin_audit_events")
public class AdminAuditEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // action / actorRole / targetType stored as enum names (varchar). actorRole carries a DB CHECK
    // like registration_events; action deliberately does not — see V3.
    @Enumerated(EnumType.STRING)
    @Column(length = 40)
    private AdminAuditAction action;

    private String actor;        // admin username, from the JWT — never a request body

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role")
    private ActorRole actorRole;

    @Enumerated(EnumType.STRING)
    @Column(name = "target_type", length = 30)
    private AuditTargetType targetType;

    /** Username, numeric id or course code — hence a String. Null only where the action has no
     *  single subject (a bulk export names its filter in {@code detail} instead). */
    @Column(name = "target_id")
    private String targetId;

    /** What changed, in a form that is still meaningful after the row it describes is gone —
     *  e.g. "role=HOD dept=CSE" on a create, "code CS -> CE" on an edit. */
    @Column(length = 500)
    private String detail;

    private Instant timestamp;

    public AdminAuditEvent() {}

    public AdminAuditEvent(AdminAuditAction action, String actor, ActorRole actorRole,
                           AuditTargetType targetType, String targetId, String detail) {
        this.action = action;
        this.actor = actor;
        this.actorRole = actorRole;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.timestamp = Instant.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public AdminAuditAction getAction() { return action; }
    public void setAction(AdminAuditAction action) { this.action = action; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public ActorRole getActorRole() { return actorRole; }
    public void setActorRole(ActorRole actorRole) { this.actorRole = actorRole; }

    public AuditTargetType getTargetType() { return targetType; }
    public void setTargetType(AuditTargetType targetType) { this.targetType = targetType; }

    public String getTargetId() { return targetId; }
    public void setTargetId(String targetId) { this.targetId = targetId; }

    public String getDetail() { return detail; }
    public void setDetail(String detail) { this.detail = detail; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
}
