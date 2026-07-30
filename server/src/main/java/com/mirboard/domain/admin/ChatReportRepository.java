package com.mirboard.domain.admin;

import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatReportRepository extends JpaRepository<ChatReport, Long> {

    boolean existsByEventIdAndReporterUserId(String eventId, Long reporterUserId);

    List<ChatReport> findAllByOrderByCreatedAtDesc(Pageable pageable);

    long countByReportedUserId(Long reportedUserId);
}
