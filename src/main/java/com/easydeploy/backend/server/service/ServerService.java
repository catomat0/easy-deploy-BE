package com.easydeploy.backend.server.service;

import com.easydeploy.backend.deploy.service.TerraformService;
import com.easydeploy.backend.server.controller.ServerResponse;
import com.easydeploy.backend.server.domain.AwsCredential;
import com.easydeploy.backend.server.domain.Server;
import com.easydeploy.backend.server.domain.ServerStatus;
import com.easydeploy.backend.server.repository.AwsCredentialRepository;
import com.easydeploy.backend.server.repository.ServerRepository;
import com.easydeploy.backend.global.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ServerService {

    private final ServerRepository serverRepository;
    private final AwsCredentialRepository awsCredentialRepository;
    private final TerraformService terraformService;
    private final EncryptionService encryptionService;

    public List<ServerResponse> getMyServers(String userEmail) {
        return serverRepository.findByUserEmailOrderByCreatedAtDesc(userEmail)
                .stream().map(ServerResponse::from).toList();
    }

    public List<ServerResponse> getRunningServers(String userEmail) {
        return serverRepository.findByUserEmailAndStatus(userEmail, ServerStatus.RUNNING)
                .stream().map(ServerResponse::from).toList();
    }

    @Transactional
    public ServerResponse terminateServer(Long serverId, String userEmail) {
        Server server = serverRepository.findById(serverId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 서버입니다."));

        if (!server.getUserEmail().equals(userEmail))
            throw new IllegalArgumentException("본인의 서버만 종료할 수 있습니다.");
        if (server.getStatus() == ServerStatus.TERMINATED)
            throw new IllegalStateException("이미 종료된 서버입니다.");

        // 즉시 TERMINATED 처리 후 응답 반환 — destroy는 백그라운드에서 실행
        server.terminate();
        serverRepository.save(server);
        log.info("[Server {}] 종료 처리 완료: instanceId={}", serverId, server.getInstanceId());

        if (server.getDeployJobId() != null) {
            Path jobDir = terraformService.getJobDir(String.valueOf(server.getDeployJobId()));
            if (Files.exists(jobDir)) {
                terraformService.destroyAsync(jobDir, "[Server " + serverId + "]");
            } else {
                log.warn("[Server {}] Terraform 워크스페이스 없음 (jobId={}), AWS 콘솔에서 수동 확인 필요",
                        serverId, server.getDeployJobId());
            }
        }

        return ServerResponse.from(server);
    }

    @Transactional
    public Server createFromDeployment(String userEmail, String instanceId, String publicIp,
                                       String eipAllocationId, String awsRegion,
                                       String instanceType, String githubRepoUrl,
                                       Long deployJobId, String deployJobUuid,
                                       String accessKeyId, String secretKey) {
        Server server = Server.builder()
                .userEmail(userEmail)
                .instanceId(instanceId)
                .publicIp(publicIp)
                .eipAllocationId(eipAllocationId)
                .awsRegion(awsRegion)
                .instanceType(instanceType)
                .githubRepoUrl(githubRepoUrl)
                .deployJobId(deployJobId)
                .deployJobUuid(deployJobUuid)
                .build();
        serverRepository.save(server);

        AwsCredential cred = AwsCredential.builder()
                .serverId(server.getId())
                .encryptedAccessKeyId(encryptionService.encrypt(accessKeyId))
                .encryptedSecretAccessKey(encryptionService.encrypt(secretKey))
                .awsRegion(awsRegion)
                .build();
        awsCredentialRepository.save(cred);

        log.info("[Server {}] 생성 및 자격증명 암호화 저장 완료", server.getId());
        return server;
    }
}
