package com.college.backlog.repository;

import com.college.backlog.model.RegistrationEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RegistrationEventRepository extends JpaRepository<RegistrationEvent, Long> {
    List<RegistrationEvent> findByRegIdOrderByTimestampAsc(String regId);
}
