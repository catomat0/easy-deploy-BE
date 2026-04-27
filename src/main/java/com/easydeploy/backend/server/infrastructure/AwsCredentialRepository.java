package com.easydeploy.backend.server.infrastructure;

import com.easydeploy.backend.server.domain.AwsCredential;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AwsCredentialRepository extends JpaRepository<AwsCredential, Long> {
    Optional<AwsCredential> findByServerId(Long serverId);
}
