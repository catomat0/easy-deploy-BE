package com.easydeploy.backend.deploy.domain;

import com.easydeploy.backend.deploy.domain.DeployJob;
import com.easydeploy.backend.deploy.domain.DeployStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class DeployJobTest {

    private DeployJob buildJob() {
        return DeployJob.builder()
                .awsRegion("ap-northeast-2")
                .instanceType("t3.micro")
                .githubRepoUrl("https://github.com/test/repo")
                .userEmail("test@example.com")
                .build();
    }

    @Test
    void 생성시_초기_상태는_PENDING() {
        DeployJob job = buildJob();
        assertThat(job.getStatus()).isEqualTo(DeployStatus.PENDING);
        assertThat(job.getCurrentStep()).isEqualTo("요청 접수됨");
        assertThat(job.getCreatedAt()).isNotNull();
        assertThat(job.getUpdatedAt()).isNotNull();
    }

    @Test
    void complete_호출시_SUCCESS_상태와_IP_저장() {
        DeployJob job = buildJob();
        job.complete("1.2.3.4", "i-1234567890abcdef0", "eipalloc-abc123");

        assertThat(job.getStatus()).isEqualTo(DeployStatus.SUCCESS);
        assertThat(job.getPublicIp()).isEqualTo("1.2.3.4");
        assertThat(job.getInstanceId()).isEqualTo("i-1234567890abcdef0");
        assertThat(job.getEipAllocationId()).isEqualTo("eipalloc-abc123");
        assertThat(job.getCurrentStep()).isEqualTo("배포 완료");
    }

    @Test
    void cancel_호출시_CANCELLED_상태와_취소_메시지() {
        DeployJob job = buildJob();
        job.updateStatus(DeployStatus.RUNNING);
        job.cancel();

        assertThat(job.getStatus()).isEqualTo(DeployStatus.CANCELLED);
        assertThat(job.getCurrentStep()).isEqualTo("배포 취소됨");
        assertThat(job.getErrorMessage()).isEqualTo("사용자가 배포를 취소했습니다.");
    }

    @Test
    void fail_호출시_FAILED_상태와_에러_메시지_저장() {
        DeployJob job = buildJob();
        job.fail("EC2 생성 중 오류 발생: 권한 없음");

        assertThat(job.getStatus()).isEqualTo(DeployStatus.FAILED);
        assertThat(job.getErrorMessage()).isEqualTo("EC2 생성 중 오류 발생: 권한 없음");
        assertThat(job.getCurrentStep()).isEqualTo("배포 실패");
    }

    @Test
    void updateStatus_상태_변경() {
        DeployJob job = buildJob();
        job.updateStatus(DeployStatus.RUNNING);
        assertThat(job.getStatus()).isEqualTo(DeployStatus.RUNNING);
    }

    @Test
    void updateStep_단계_메시지_변경() {
        DeployJob job = buildJob();
        job.updateStep("EC2 인스턴스 생성 중...");
        assertThat(job.getCurrentStep()).isEqualTo("EC2 인스턴스 생성 중...");
    }

    @Test
    void fail_후_errorMessage_null_허용() {
        DeployJob job = buildJob();
        job.fail(null);
        assertThat(job.getStatus()).isEqualTo(DeployStatus.FAILED);
        assertThat(job.getErrorMessage()).isNull();
    }
}
