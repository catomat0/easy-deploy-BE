package com.easydeploy.backend.server.infrastructure;

import com.easydeploy.backend.server.domain.AwsCredential;
import com.easydeploy.backend.server.domain.Server;
import com.easydeploy.backend.server.domain.ServerStatus;
import com.easydeploy.backend.shared.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.DescribeInstancesRequest;
import software.amazon.awssdk.services.ec2.model.InstanceStateName;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class AwsSyncScheduler {

    private static final String SEOUL_REGION = "ap-northeast-2";

    private final ServerRepository serverRepository;
    private final AwsCredentialRepository awsCredentialRepository;
    private final EncryptionService encryptionService;

    @Scheduled(fixedDelay = 600_000)
    @Transactional
    public void syncServerStatus() {
        List<Server> targets = serverRepository.findRunningSortedBySyncedAt(
                ServerStatus.RUNNING, SEOUL_REGION);

        if (targets.isEmpty()) return;
        log.info("[Sync] 서울 리전 RUNNING 서버 {}개 동기화 시작", targets.size());

        Map<String, List<Server>> byUser = targets.stream()
                .collect(Collectors.groupingBy(Server::getUserEmail));

        for (Map.Entry<String, List<Server>> entry : byUser.entrySet()) {
            String userEmail = entry.getKey();
            List<Server> userServers = entry.getValue();

            AwsCredential cred = null;
            for (Server s : userServers) {
                cred = awsCredentialRepository.findByServerId(s.getId()).orElse(null);
                if (cred != null) break;
            }
            if (cred == null) {
                log.warn("[Sync] {} 자격증명 없음, 건너뜀", userEmail);
                continue;
            }

            try {
                String accessKeyId = encryptionService.decrypt(cred.getEncryptedAccessKeyId());
                String secretKey   = encryptionService.decrypt(cred.getEncryptedSecretAccessKey());

                Ec2Client ec2 = Ec2Client.builder()
                        .region(Region.of(SEOUL_REGION))
                        .credentialsProvider(StaticCredentialsProvider.create(
                                AwsBasicCredentials.create(accessKeyId, secretKey)))
                        .build();

                List<String> instanceIds = userServers.stream().map(Server::getInstanceId).toList();
                var result = ec2.describeInstances(
                        DescribeInstancesRequest.builder().instanceIds(instanceIds).build());
                ec2.close();

                var stateMap = result.reservations().stream()
                        .flatMap(r -> r.instances().stream())
                        .collect(Collectors.toMap(i -> i.instanceId(), i -> i.state().name()));

                for (Server server : userServers) {
                    InstanceStateName state = stateMap.getOrDefault(
                            server.getInstanceId(), InstanceStateName.TERMINATED);
                    if (state == InstanceStateName.TERMINATED || state == InstanceStateName.SHUTTING_DOWN) {
                        server.terminate();
                        log.info("[Sync] Server {} → AWS {} → TERMINATED", server.getId(), state);
                    } else {
                        server.markSynced();
                    }
                    serverRepository.save(server);
                }
                log.info("[Sync] {} 서버 {}개 완료", userEmail, userServers.size());
            } catch (Exception e) {
                log.error("[Sync] {} AWS 조회 실패: {}", userEmail, e.getMessage());
            }
        }
    }
}
