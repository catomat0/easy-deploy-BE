package com.easydeploy.backend.deploy.application;

import com.easydeploy.backend.deploy.domain.DeployJob;
import com.easydeploy.backend.deploy.domain.DeployStatus;
import com.easydeploy.backend.deploy.infrastructure.DeployJobRepository;
import com.easydeploy.backend.deploy.infrastructure.GithubActionsService;
import com.easydeploy.backend.deploy.infrastructure.TerraformService;
import com.easydeploy.backend.deploy.presentation.DeployRequest;
import com.easydeploy.backend.deploy.presentation.DeployResponse;
import com.easydeploy.backend.server.application.ServerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Service
@RequiredArgsConstructor
public class DeployService {

    private final DeployJobRepository deployJobRepository;
    private final TerraformService terraformService;
    private final GithubActionsService githubActionsService;
    private final ServerService serverService;
    private final TaskExecutor deployTaskExecutor;
    private final ObjectMapper objectMapper;

    // SSE 연결 관리: jobId → 연결된 Emitter 목록
    private final Map<Long, List<SseEmitter>> emitters = new ConcurrentHashMap<>();

    private static final int MAX_CONCURRENT_DEPLOYS = 1;

    // ── SSE ───────────────────────────────────────────────────────────────────

    public SseEmitter createSseEmitter(String uuid) {
        DeployJob job = findJobByUuid(uuid);
        Long dbId = job.getId();

        SseEmitter emitter = new SseEmitter(300_000L);
        emitters.computeIfAbsent(dbId, k -> new CopyOnWriteArrayList<>()).add(emitter);

        emitter.onCompletion(() -> removeEmitter(dbId, emitter));
        emitter.onTimeout(() -> removeEmitter(dbId, emitter));
        emitter.onError(e -> removeEmitter(dbId, emitter));

        try {
            emitter.send(SseEmitter.event()
                    .name("status")
                    .data(objectMapper.writeValueAsString(DeployResponse.from(job))));
        } catch (Exception e) {
            log.warn("[Job {}] SSE 초기 상태 전송 실패: {}", uuid, e.getMessage());
        }

        return emitter;
    }

    private void removeEmitter(Long jobId, SseEmitter emitter) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list != null) list.remove(emitter);
    }

    private void broadcastStep(Long jobId, String step) {
        List<SseEmitter> list = emitters.get(jobId);
        if (list == null || list.isEmpty()) return;
        List<SseEmitter> dead = new ArrayList<>();
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("step")
                        .data(objectMapper.writeValueAsString(Map.of("step", step))));
            } catch (Exception e) {
                dead.add(emitter);
            }
        }
        if (!dead.isEmpty()) list.removeAll(dead);
    }

    private void broadcastFinal(Long jobId, DeployJob job) {
        broadcastFinal(jobId, job, null);
    }

    /** 배포 성공 시에만 sshPrivateKey를 포함해 전송 — 이 한 번의 SSE 이벤트 외에는 키를 보내지 않음 */
    private void broadcastFinal(Long jobId, DeployJob job, String sshPrivateKey) {
        List<SseEmitter> list = emitters.getOrDefault(jobId, List.of());
        DeployResponse payload = sshPrivateKey != null
                ? DeployResponse.fromWithSshKey(job, sshPrivateKey)
                : DeployResponse.from(job);
        for (SseEmitter emitter : list) {
            try {
                emitter.send(SseEmitter.event()
                        .name("complete")
                        .data(objectMapper.writeValueAsString(payload)));
                emitter.complete();
            } catch (Exception ignored) {}
        }
        emitters.remove(jobId);
    }

    // ── 배포 로직 ─────────────────────────────────────────────────────────────

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void recoverStaleJobs() {
        List<DeployJob> staleJobs = deployJobRepository.findByStatus(DeployStatus.RUNNING);
        if (!staleJobs.isEmpty()) {
            log.warn("서버 재시작으로 {}개의 미완료 Job을 FAILED 처리합니다.", staleJobs.size());
            staleJobs.forEach(job ->
                    job.fail("서버가 재시작되어 배포 결과를 알 수 없습니다. AWS 콘솔에서 리소스를 확인하세요."));
            deployJobRepository.saveAll(staleJobs);
        }
    }

    public DeployResponse startDeploy(DeployRequest req, String userEmail) {
        long activeCount = deployJobRepository.countByUserEmailAndStatusIn(
                userEmail, List.of(DeployStatus.PENDING, DeployStatus.RUNNING)
        );
        if (activeCount >= MAX_CONCURRENT_DEPLOYS) {
            throw new IllegalStateException("이미 진행 중인 배포가 있습니다. 완료 후 다시 시도하세요.");
        }

        DeployJob job = DeployJob.builder()
                .awsRegion(req.getAwsRegion())
                .instanceType(req.getInstanceType())
                .githubRepoUrl(req.getGithubRepoUrl())
                .userEmail(userEmail)
                .build();
        deployJobRepository.save(job);

        // TaskExecutor로 실행 — Spring 프록시를 통해 진짜 비동기 실행됨 (self-call 우회)
        Long jobId = job.getId();
        deployTaskExecutor.execute(() -> runDeploy(jobId, req));

        return DeployResponse.from(job);
    }

    public DeployResponse getStatus(String uuid) {
        return DeployResponse.from(findJobByUuid(uuid));
    }

    public List<Map<String, String>> getTerraformFiles(String uuid, String userEmail) {
        DeployJob job = findJobByUuid(uuid);
        if (!job.getUserEmail().equals(userEmail)) {
            throw new IllegalArgumentException("본인의 배포 작업만 조회할 수 있습니다.");
        }
        Path jobDir = terraformService.getJobDir(String.valueOf(job.getId()));
        try {
            return terraformService.readTerraformFiles(jobDir);
        } catch (Exception e) {
            throw new RuntimeException("Terraform 파일 조회 실패: " + e.getMessage(), e);
        }
    }

    @Transactional
    public DeployResponse cancelDeploy(String uuid, String userEmail) {
        DeployJob job = findJobByUuid(uuid);
        Long dbId = job.getId();

        if (!job.getUserEmail().equals(userEmail)) {
            throw new IllegalArgumentException("본인의 배포만 취소할 수 있습니다.");
        }
        if (job.getStatus() != DeployStatus.RUNNING && job.getStatus() != DeployStatus.PENDING) {
            throw new IllegalStateException("진행 중인 배포만 취소할 수 있습니다.");
        }

        // 즉시 CANCELLED 처리 후 응답 반환 — destroy는 백그라운드에서 실행
        job.cancel();
        deployJobRepository.save(job);
        broadcastFinal(dbId, job);

        Path jobDir = terraformService.getJobDir(String.valueOf(dbId));
        if (Files.exists(jobDir)) {
            terraformService.destroyAsync(jobDir, "[Job " + uuid + "]");
        }

        return DeployResponse.from(job);
    }

    // ── 배포 실행 (비동기 스레드에서 실행) ────────────────────────────────────

    private void runDeploy(Long jobId, DeployRequest req) {
        DeployJob job = findJob(jobId);
        job.updateStatus(DeployStatus.RUNNING);
        deployJobRepository.save(job);

        boolean terraformDone = false;

        try {
            step(job, "Terraform 워크스페이스 초기화 중...");
            broadcastStep(jobId, "Terraform 워크스페이스 초기화 중...");

            String userData = terraformService.buildUserData(
                    req.getGithubRepoUrl(), req.getGithubToken(), req.getEnvProd());

            Map<String, Object> tfvars = new HashMap<>();
            tfvars.put("aws_access_key_id", req.getAwsAccessKeyId());
            tfvars.put("aws_secret_access_key", req.getAwsSecretAccessKey());
            tfvars.put("aws_region", req.getAwsRegion());
            tfvars.put("instance_type", req.getInstanceType());
            tfvars.put("job_id", job.getUuid());
            tfvars.put("user_data", userData);
            tfvars.put("storage_size", req.getStorageSize());
            if (req.getVpcId() != null && !req.getVpcId().isBlank())
                tfvars.put("vpc_id", req.getVpcId());
            if (req.getSubnetId() != null && !req.getSubnetId().isBlank())
                tfvars.put("subnet_id", req.getSubnetId());

            Path jobDir = terraformService.setupWorkspace(String.valueOf(jobId), tfvars);

            step(job, "Terraform init 중...");
            broadcastStep(jobId, "Terraform init 중...");
            terraformService.init(jobDir);

            step(job, "인프라 배포 중 (약 2~3분 소요)...");
            broadcastStep(jobId, "인프라 배포 중 (약 2~3분 소요)...");
            terraformService.apply(jobDir, progressMsg -> {
                step(job, progressMsg);
                broadcastStep(jobId, progressMsg);
            });

            Map<String, String> outputs = terraformService.output(jobDir);
            String instanceId      = outputs.getOrDefault("instance_id", "");
            String publicIp        = outputs.getOrDefault("public_ip", "");
            String eipAllocationId = outputs.getOrDefault("eip_allocation_id", "");
            String sshKey          = outputs.getOrDefault("ssh_private_key", "");
            log.info("[Job {}] EC2 완료: ip={}, instance={}", jobId, publicIp, instanceId);

            // apply 완료 — user_data(GitHub 토큰 포함)를 tfvars에서 제거, AWS 키는 destroy에 필요해 유지
            Map<String, Object> sanitized = new HashMap<>();
            sanitized.put("aws_access_key_id", req.getAwsAccessKeyId());
            sanitized.put("aws_secret_access_key", req.getAwsSecretAccessKey());
            sanitized.put("aws_region", req.getAwsRegion());
            sanitized.put("instance_type", req.getInstanceType());
            sanitized.put("job_id", job.getUuid());
            sanitized.put("user_data", "");
            sanitized.put("storage_size", req.getStorageSize());
            sanitized.put("vpc_id", req.getVpcId() != null ? req.getVpcId() : "");
            sanitized.put("subnet_id", req.getSubnetId() != null ? req.getSubnetId() : "");
            terraformService.sanitizeTfvars(jobDir, sanitized);

            terraformDone = true;

            step(job, "GitHub Actions CD 파이프라인 설정 중...");
            broadcastStep(jobId, "GitHub Actions CD 파이프라인 설정 중...");
            githubActionsService.setup(
                    req.getGithubToken(), req.getGithubRepoUrl(),
                    publicIp, sshKey, req.getEnvProd());

            job.complete(publicIp, instanceId, eipAllocationId);
            deployJobRepository.save(job);

            serverService.createFromDeployment(
                    job.getUserEmail(), instanceId, publicIp, eipAllocationId,
                    req.getAwsRegion(), req.getInstanceType(), req.getGithubRepoUrl(),
                    jobId, job.getUuid(),
                    req.getAwsAccessKeyId(), req.getAwsSecretAccessKey()
            );

            log.info("[Job {}] 배포 전체 완료: ip={}", jobId, publicIp);
            broadcastFinal(jobId, findJob(jobId), sshKey);

        } catch (Exception e) {
            log.error("[Job {}] 배포 실패 ({}): {}", jobId, e.getClass().getSimpleName(), e.getMessage(), e);

            if (!terraformDone) {
                Path jobDir = terraformService.getJobDir(String.valueOf(jobId));
                if (Files.exists(jobDir.resolve("terraform.tfstate"))) {
                    try {
                        broadcastStep(jobId, "배포 실패 — 생성된 AWS 리소스 자동 정리 중...");
                        terraformService.destroy(jobDir,
                                msg -> log.info("[Job {}] cleanup: {}", jobId, msg));
                        log.info("[Job {}] 실패 후 리소스 정리 완료", jobId);
                    } catch (Exception destroyEx) {
                        log.error("[Job {}] 리소스 자동 정리 실패 — AWS 콘솔에서 수동 확인 필요: {}",
                                jobId, destroyEx.getMessage());
                    }
                }
            } else {
                log.warn("[Job {}] terraform 이후 단계 실패 — EC2는 유지됨. 수동으로 GitHub Actions를 설정하거나 서버를 종료하세요.", jobId);
            }

            DeployJob fresh = findJob(jobId);
            if (fresh.getStatus() != DeployStatus.FAILED) {
                fresh.fail(e.getMessage());
                deployJobRepository.save(fresh);
            }
            broadcastFinal(jobId, fresh);
        }
    }

    // ── 내부 유틸 ─────────────────────────────────────────────────────────────

    private void step(DeployJob job, String message) {
        job.updateStep(message);
        deployJobRepository.save(job);
        log.info("[Job {}] {}", job.getId(), message);
    }

    private DeployJob findJob(Long jobId) {
        return deployJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Job ID: " + jobId));
    }

    private DeployJob findJobByUuid(String uuid) {
        return deployJobRepository.findByUuid(uuid)
                .orElseThrow(() -> new IllegalArgumentException("존재하지 않는 Job: " + uuid));
    }
}
