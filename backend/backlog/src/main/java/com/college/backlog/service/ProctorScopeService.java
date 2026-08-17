package com.college.backlog.service;

import com.college.backlog.model.ProctorAssignment;
import com.college.backlog.model.User;
import com.college.backlog.model.UserRole;
import com.college.backlog.repository.ProctorAssignmentRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;
import java.util.stream.Collectors;

/**
 * Proctor scope primitives. A PROCTOR is dept-pinned like HOD/DEPT_OFFICE (User.department matched
 * by USN branch code) and additionally hard-scoped to explicitly assigned students
 * (proctor_students). Outside that set every read and write fails closed — controllers call these
 * server-side, the UI only mirrors the result.
 */
@Service
public class ProctorScopeService {

    @Autowired
    private ProctorAssignmentRepository assignmentRepository;

    public boolean isProctor(User actor) {
        return actor != null && actor.getRole() == UserRole.PROCTOR;
    }

    /**
     * Roll numbers this actor supervises; {@code null} for a non-proctor, meaning unrestricted at
     * the assignment layer (dept scope still applies separately). May be empty — a proctor with no
     * assignments sees nothing.
     */
    public Set<String> assignedRollNos(User actor) {
        if (!isProctor(actor)) return null;
        return assignmentRepository.findByProctorUsername(actor.getUsername()).stream()
                .map(ProctorAssignment::getRollNo)
                .collect(Collectors.toSet());
    }

    /** 403 unless the actor is a non-proctor or supervises this student. */
    public void assertSupervises(User actor, String rollNo) {
        if (!isProctor(actor)) return;
        boolean supervised = assignmentRepository.findById(rollNo)
                .map(a -> a.getProctorUsername().equals(actor.getUsername()))
                .orElse(false);
        if (!supervised) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                "This student is not under your supervision.");
        }
    }

    /** 403 for proctors — used on the endpoints a proctor may never call. */
    public void rejectProctor(User actor, String message) {
        if (isProctor(actor)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, message);
        }
    }
}
