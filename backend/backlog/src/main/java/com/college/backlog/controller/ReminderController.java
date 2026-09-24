package com.college.backlog.controller;

import com.college.backlog.controller.dto.ReminderConfig;
import com.college.backlog.controller.dto.ReminderFailureItem;
import com.college.backlog.controller.dto.ReminderListItem;
import com.college.backlog.controller.dto.ReminderPreview;
import com.college.backlog.controller.dto.ReminderRequest;
import com.college.backlog.controller.dto.ReminderTestRequest;
import com.college.backlog.service.CallerScope;
import com.college.backlog.service.reminder.ReminderService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled reminder emails. ADMIN only, class-wide, with no widened method: this emails students
 * college-wide, which is not a departmental power. SecurityConfig's /api/admin/** rule admits all
 * five staff roles, so this annotation is the control.
 */
@RestController
@RequestMapping("/api/admin/reminders")
@PreAuthorize("hasRole('ADMIN')")
public class ReminderController {

    @Autowired private ReminderService reminderService;
    @Autowired private CallerScope callerScope;

    @GetMapping
    public List<ReminderListItem> list() {
        return reminderService.list();
    }

    @GetMapping("/config")
    public ReminderConfig config() {
        return reminderService.config();
    }

    @PostMapping("/preview")
    public ReminderPreview preview(@RequestBody ReminderRequest req) {
        return reminderService.preview(req);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ReminderListItem create(@RequestBody ReminderRequest req, Authentication auth) {
        return reminderService.create(req, callerScope.requireActor(auth));
    }

    @PostMapping("/{id}/cancel")
    public ReminderListItem cancel(@PathVariable Long id, Authentication auth) {
        return reminderService.cancel(id, callerScope.requireActor(auth));
    }

    @GetMapping("/{id}/failures")
    public List<ReminderFailureItem> failures(@PathVariable Long id) {
        return reminderService.failures(id);
    }

    @PostMapping("/test")
    public Map<String, String> sendTest(@RequestBody ReminderTestRequest req, Authentication auth) {
        String warning = reminderService.sendTest(req, callerScope.requireActor(auth));
        Map<String, String> body = new HashMap<>();
        body.put("status", "sent");
        if (warning != null) {
            body.put("warning", warning);
        }
        return body;
    }
}
