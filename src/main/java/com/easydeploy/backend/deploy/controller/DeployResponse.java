package com.easydeploy.backend.deploy.controller;

import com.easydeploy.backend.deploy.domain.DeployJob;
import com.easydeploy.backend.deploy.domain.DeployStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class DeployResponse {

    private String jobId;
    private DeployStatus status;
    private String currentStep;
    private String publicIp;
    private String instanceId;
    private String eipAllocationId;
    private String errorMessage;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    /** 배포 완료 SSE 이벤트에만 포함, 이후 조회 API에서는 항상 null */
    private String sshPrivateKey;

    public static DeployResponse from(DeployJob job) {
        return DeployResponse.builder()
                .jobId(job.getUuid())
                .status(job.getStatus())
                .currentStep(job.getCurrentStep())
                .publicIp(job.getPublicIp())
                .instanceId(job.getInstanceId())
                .eipAllocationId(job.getEipAllocationId())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .build();
    }

    public static DeployResponse fromWithSshKey(DeployJob job, String sshPrivateKey) {
        return DeployResponse.builder()
                .jobId(job.getUuid())
                .status(job.getStatus())
                .currentStep(job.getCurrentStep())
                .publicIp(job.getPublicIp())
                .instanceId(job.getInstanceId())
                .eipAllocationId(job.getEipAllocationId())
                .errorMessage(job.getErrorMessage())
                .createdAt(job.getCreatedAt())
                .updatedAt(job.getUpdatedAt())
                .sshPrivateKey(sshPrivateKey)
                .build();
    }
}
