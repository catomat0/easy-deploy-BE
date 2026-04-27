package com.easydeploy.backend.domain.server.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class Server {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String userEmail;
    private String instanceId;
    private String publicIp;
    private String eipAllocationId;
    private String awsRegion;
    private String instanceType;
    private String githubRepoUrl;

    @Enumerated(EnumType.STRING)
    private ServerStatus status;

    private Long deployJobId;
    private String deployJobUuid;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private LocalDateTime lastSyncedAt;

    @Builder
    public Server(String userEmail, String instanceId, String publicIp, String eipAllocationId,
                  String awsRegion, String instanceType, String githubRepoUrl,
                  Long deployJobId, String deployJobUuid) {
        this.userEmail = userEmail;
        this.instanceId = instanceId;
        this.publicIp = publicIp;
        this.eipAllocationId = eipAllocationId;
        this.awsRegion = awsRegion;
        this.instanceType = instanceType;
        this.githubRepoUrl = githubRepoUrl;
        this.deployJobId = deployJobId;
        this.deployJobUuid = deployJobUuid;
        this.status = ServerStatus.RUNNING;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void terminate() {
        this.status = ServerStatus.TERMINATED;
        this.updatedAt = LocalDateTime.now();
    }

    public void markSynced() {
        this.lastSyncedAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }
}
