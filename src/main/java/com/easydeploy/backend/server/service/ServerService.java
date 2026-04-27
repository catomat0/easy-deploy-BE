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
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.Filter;

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
    private final TaskExecutor deployTaskExecutor;

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

        server.terminate();
        serverRepository.save(server);
        log.info("[Server {}] 종료 처리 완료: instanceId={}", serverId, server.getInstanceId());

        Path jobDir = server.getDeployJobId() != null
                ? terraformService.getJobDir(String.valueOf(server.getDeployJobId()))
                : null;

        if (jobDir != null && Files.exists(jobDir)) {
            terraformService.destroyAsync(jobDir, "[Server " + serverId + "]");
        } else {
            // Terraform workspace가 없는 경우 (컨테이너 재시작 등으로 tfstate 유실)
            // AWS SDK로 직접 리소스 정리
            log.warn("[Server {}] Terraform 워크스페이스 없음 — AWS SDK로 직접 정리 시도 (jobId={})",
                    serverId, server.getDeployJobId());
            deployTaskExecutor.execute(() -> cleanupAwsResources(server, serverId));
        }

        return ServerResponse.from(server);
    }

    private void cleanupAwsResources(Server server, Long serverId) {
        AwsCredential cred = awsCredentialRepository.findByServerId(serverId).orElse(null);
        if (cred == null) {
            log.error("[Server {}] AWS 자격증명 없음 — AWS 콘솔에서 수동 정리 필요", serverId);
            return;
        }

        Ec2Client ec2 = null;
        try {
            String accessKeyId = encryptionService.decrypt(cred.getEncryptedAccessKeyId());
            String secretKey   = encryptionService.decrypt(cred.getEncryptedSecretAccessKey());

            ec2 = Ec2Client.builder()
                    .region(Region.of(server.getAwsRegion()))
                    .credentialsProvider(StaticCredentialsProvider.create(
                            AwsBasicCredentials.create(accessKeyId, secretKey)))
                    .build();

            // 1. EC2 인스턴스 종료
            ec2.terminateInstances(r -> r.instanceIds(server.getInstanceId()));
            log.info("[Server {}] EC2 인스턴스 종료 요청: {}", serverId, server.getInstanceId());

            // 2. EIP 즉시 해제 (인스턴스 종료 시 자동 비연결)
            if (server.getEipAllocationId() != null && !server.getEipAllocationId().isBlank()) {
                try {
                    ec2.releaseAddress(r -> r.allocationId(server.getEipAllocationId()));
                    log.info("[Server {}] EIP 해제: {}", serverId, server.getEipAllocationId());
                } catch (Exception e) {
                    log.warn("[Server {}] EIP 해제 실패: {}", serverId, e.getMessage());
                }
            }

            // 3. 인스턴스 완전 종료 대기 후 나머지 리소스 정리
            String deployJobUuid = server.getDeployJobUuid();
            if (deployJobUuid != null) {
                final Ec2Client ec2Ref = ec2;
                log.info("[Server {}] 인스턴스 종료 대기 중...", serverId);
                ec2.waiter().waitUntilInstanceTerminated(
                        r -> r.instanceIds(server.getInstanceId()));

                // 4. 보안그룹 삭제
                try {
                    var sgs = ec2Ref.describeSecurityGroups(r -> r.filters(
                            Filter.builder().name("group-name")
                                    .values("easydeploy-sg-" + deployJobUuid).build()));
                    sgs.securityGroups().forEach(sg -> {
                        try {
                            ec2Ref.deleteSecurityGroup(r -> r.groupId(sg.groupId()));
                            log.info("[Server {}] 보안그룹 삭제: {}", serverId, sg.groupId());
                        } catch (Exception e) {
                            log.warn("[Server {}] 보안그룹 삭제 실패: {}", serverId, e.getMessage());
                        }
                    });
                } catch (Exception e) {
                    log.warn("[Server {}] 보안그룹 조회/삭제 실패: {}", serverId, e.getMessage());
                }

                // 5. 키페어 삭제
                try {
                    ec2Ref.deleteKeyPair(r -> r.keyName("easydeploy-keypair-" + deployJobUuid));
                    log.info("[Server {}] 키페어 삭제 완료", serverId);
                } catch (Exception e) {
                    log.warn("[Server {}] 키페어 삭제 실패: {}", serverId, e.getMessage());
                }
            }

            log.info("[Server {}] AWS 리소스 정리 완료", serverId);
        } catch (Exception e) {
            log.error("[Server {}] AWS 리소스 정리 실패 — AWS 콘솔에서 수동 확인 필요: {}", serverId, e.getMessage());
        } finally {
            if (ec2 != null) ec2.close();
        }
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
