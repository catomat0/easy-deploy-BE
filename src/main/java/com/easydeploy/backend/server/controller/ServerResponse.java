package com.easydeploy.backend.server.controller;

import com.easydeploy.backend.server.domain.Server;
import com.easydeploy.backend.server.domain.ServerStatus;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
public class ServerResponse {

    private Long id;
    private String instanceId;
    private String publicIp;
    private String awsRegion;
    private String instanceType;
    private String githubRepoUrl;
    private ServerStatus status;
    private String deployJobUuid;
    private LocalDateTime createdAt;

    public static ServerResponse from(Server server) {
        return ServerResponse.builder()
                .id(server.getId())
                .instanceId(server.getInstanceId())
                .publicIp(server.getPublicIp())
                .awsRegion(server.getAwsRegion())
                .instanceType(server.getInstanceType())
                .githubRepoUrl(server.getGithubRepoUrl())
                .status(server.getStatus())
                .deployJobUuid(server.getDeployJobUuid())
                .createdAt(server.getCreatedAt())
                .build();
    }
}
