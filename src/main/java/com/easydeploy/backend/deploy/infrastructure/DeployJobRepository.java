package com.easydeploy.backend.deploy.infrastructure;

import com.easydeploy.backend.deploy.domain.DeployJob;
import com.easydeploy.backend.deploy.domain.DeployStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeployJobRepository extends JpaRepository<DeployJob, Long> {
    List<DeployJob> findByStatus(DeployStatus status);
    long countByUserEmailAndStatusIn(String userEmail, List<DeployStatus> statuses);
    Optional<DeployJob> findByUuid(String uuid);
}
