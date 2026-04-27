package com.easydeploy.backend.deploy.service;

import com.easydeploy.backend.deploy.controller.DeployRequest;
import com.easydeploy.backend.deploy.controller.DeployResponse;
import com.easydeploy.backend.deploy.domain.DeployJob;
import com.easydeploy.backend.deploy.domain.DeployStatus;
import com.easydeploy.backend.deploy.repository.DeployJobRepository;
import com.easydeploy.backend.deploy.service.DeployService;
import com.easydeploy.backend.deploy.service.GithubActionsService;
import com.easydeploy.backend.deploy.service.TerraformService;
import com.easydeploy.backend.server.service.ServerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Optional;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DeployServiceTest {

    @Mock DeployJobRepository deployJobRepository;
    @Mock
    TerraformService terraformService;
    @Mock
    GithubActionsService githubActionsService;
    @Mock ServerService serverService;

    DeployService deployService;

    @BeforeEach
    void setUp() {
        deployService = new DeployService(
                deployJobRepository, terraformService, githubActionsService,
                serverService, new SyncTaskExecutor(), new com.fasterxml.jackson.databind.ObjectMapper());
    }

    @Test
    void 배포_시작_PENDING_상태로_생성() {
        when(deployJobRepository.countByUserEmailAndStatusIn(any(), any())).thenReturn(0L);
        when(deployJobRepository.save(any())).thenAnswer(inv -> {
            DeployJob job = inv.getArgument(0);
            ReflectionTestUtils.setField(job, "id", 1L);
            return job;
        });
        when(deployJobRepository.findById(1L)).thenReturn(Optional.of(stubJob(1L)));
        when(terraformService.getJobDir(any())).thenReturn(java.nio.file.Path.of("/tmp/nonexistent"));

        DeployResponse resp = deployService.startDeploy(stubRequest(), "user@test.com");

        assertThat(resp.getStatus()).isEqualTo(DeployStatus.PENDING);
        verify(deployJobRepository, atLeastOnce()).save(any());
    }

    @Test
    void 동시_배포_제한_초과시_예외() {
        when(deployJobRepository.countByUserEmailAndStatusIn(any(), any())).thenReturn(1L);

        assertThatThrownBy(() -> deployService.startDeploy(stubRequest(), "user@test.com"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 상태_조회_존재하지_않는_uuid_예외() {
        when(deployJobRepository.findByUuid("nonexistent-uuid")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> deployService.getStatus("nonexistent-uuid"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void 배포_취소_CANCELLED_상태로_변경() {
        DeployJob job = stubJob(1L);
        job.updateStatus(DeployStatus.RUNNING);
        when(deployJobRepository.findByUuid("test-uuid")).thenReturn(Optional.of(job));
        when(deployJobRepository.save(any())).thenReturn(job);
        when(terraformService.getJobDir(any())).thenReturn(java.nio.file.Path.of("/tmp/nonexistent"));

        DeployResponse resp = deployService.cancelDeploy("test-uuid", "user@test.com");

        assertThat(resp.getStatus()).isEqualTo(DeployStatus.CANCELLED);
    }

    @Test
    void 다른_사용자_배포_취소_실패() {
        DeployJob job = stubJob(1L);
        job.updateStatus(DeployStatus.RUNNING);
        when(deployJobRepository.findByUuid("test-uuid")).thenReturn(Optional.of(job));

        assertThatThrownBy(() -> deployService.cancelDeploy("test-uuid", "other@test.com"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private DeployJob stubJob(Long id) {
        DeployJob job = DeployJob.builder()
                .awsRegion("ap-northeast-2")
                .instanceType("t3.micro")
                .githubRepoUrl("https://github.com/test/repo")
                .userEmail("user@test.com")
                .build();
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    private DeployRequest stubRequest() {
        DeployRequest req = new DeployRequest();
        ReflectionTestUtils.setField(req, "awsAccessKeyId", "AKIATEST");
        ReflectionTestUtils.setField(req, "awsSecretAccessKey", "secret");
        ReflectionTestUtils.setField(req, "awsRegion", "ap-northeast-2");
        ReflectionTestUtils.setField(req, "instanceType", "t3.micro");
        ReflectionTestUtils.setField(req, "githubRepoUrl", "https://github.com/test/repo");
        ReflectionTestUtils.setField(req, "githubToken", "ghp_test");
        ReflectionTestUtils.setField(req, "envProd", "PORT=8080");
        return req;
    }
}
