package com.college.backlog.repository;

import com.college.backlog.model.AdminAuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

/** Append-only: inserts and reads only. No update or delete path exists, deliberately. */
public interface AdminAuditEventRepository extends JpaRepository<AdminAuditEvent, Long> {
}
