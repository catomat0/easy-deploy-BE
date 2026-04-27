package com.easydeploy.backend.deploy.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Getter
@NoArgsConstructor
public class DeployJob {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, updatable = false)
    private String uuid;

    private String awsRegion;
    private String instanceType;
    private String githubRepoUrl;
    private String userEmail;

    @Enumerated(EnumType.STRING)
    private DeployStatus status;

    @Column(columnDefinition = "TEXT")
    private String currentStep;

    private String publicIp;
    private String instanceId;
    private String eipAllocationId;

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;

    @Builder
    public DeployJob(String awsRegion, String instanceType, String githubRepoUrl, String userEmail) {
        this.uuid = UUID.randomUUID().toString();
        this.awsRegion = awsRegion;
        this.instanceType = instanceType;
        this.githubRepoUrl = githubRepoUrl;
        this.userEmail = userEmail;
        this.status = DeployStatus.PENDING;
        this.currentStep = "요청 접수됨";
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public void updateStatus(DeployStatus status) {
        this.status = status;
        this.updatedAt = LocalDateTime.now();
    }

    public void updateStep(String step) {
        this.currentStep = step;
        this.updatedAt = LocalDateTime.now();
    }

    public void complete(String publicIp, String instanceId, String eipAllocationId) {
        this.publicIp = publicIp;
        this.instanceId = instanceId;
        this.eipAllocationId = eipAllocationId;
        this.status = DeployStatus.SUCCESS;
        this.currentStep = "배포 완료";
        this.updatedAt = LocalDateTime.now();
    }

    public void cancel() {
        this.status = DeployStatus.CANCELLED;
        this.currentStep = "배포 취소됨";
        this.errorMessage = "사용자가 배포를 취소했습니다.";
        this.updatedAt = LocalDateTime.now();
    }

    public void fail(String errorMessage) {
        this.errorMessage = errorMessage;
        this.status = DeployStatus.FAILED;
        this.currentStep = "배포 실패";
        this.updatedAt = LocalDateTime.now();
    }
}
