package com.college.backlog.model;

import jakarta.persistence.*;
import java.time.Instant;

/** Append-only audit record of an action on a registration; rows are never updated or deleted. */
@Entity
@Table(name = "registration_events")
public class RegistrationEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // FK to registrations.reg_id is enforced at the DB level; a plain String here, to keep the
    // append-only audit design.
    @Column(name = "reg_id")
    private String regId;

    // action / actorRole stored as enum names (varchar), with DB CHECK constraints on the values.
    @Enumerated(EnumType.STRING)
    private EventAction action;

    private String actor;        // student rollNo, or admin username

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_role")
    private ActorRole actorRole;

    private Instant timestamp;

    @Column(length = 500)
    private String note;

    public RegistrationEvent() {}

    public RegistrationEvent(String regId, EventAction action, String actor, ActorRole actorRole, String note) {
        this.regId = regId;
        this.action = action;
        this.actor = actor;
        this.actorRole = actorRole;
        this.timestamp = Instant.now();
        this.note = note;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getRegId() { return regId; }
    public void setRegId(String regId) { this.regId = regId; }

    public EventAction getAction() { return action; }
    public void setAction(EventAction action) { this.action = action; }

    public String getActor() { return actor; }
    public void setActor(String actor) { this.actor = actor; }

    public ActorRole getActorRole() { return actorRole; }
    public void setActorRole(ActorRole actorRole) { this.actorRole = actorRole; }

    public Instant getTimestamp() { return timestamp; }
    public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
}
