package com.easydeploy.backend.server.repository;

import com.easydeploy.backend.server.domain.Server;
import com.easydeploy.backend.server.domain.ServerStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ServerRepository extends JpaRepository<Server, Long> {

    List<Server> findByUserEmailOrderByCreatedAtDesc(String userEmail);

    List<Server> findByUserEmailAndStatus(String userEmail, ServerStatus status);

    @Query("""
        SELECT s FROM Server s
        WHERE s.status = :status AND s.awsRegion = :region
        ORDER BY s.lastSyncedAt ASC NULLS FIRST
    """)
    List<Server> findRunningSortedBySyncedAt(
            @Param("status") ServerStatus status,
            @Param("region") String region
    );
}
