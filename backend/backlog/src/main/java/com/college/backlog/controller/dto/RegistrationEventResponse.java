package com.college.backlog.controller.dto;

public class RegistrationEventResponse {
    private String action;
    private String actor;
    private String actorRole;
    private String timestamp;
    private String note;

    public RegistrationEventResponse(String action, String actor, String actorRole, String timestamp, String note) {
        this.action = action;
        this.actor = actor;
        this.actorRole = actorRole;
        this.timestamp = timestamp;
        this.note = note;
    }

    public String getAction() { return action; }
    public String getActor() { return actor; }
    public String getActorRole() { return actorRole; }
    public String getTimestamp() { return timestamp; }
    public String getNote() { return note; }
}
