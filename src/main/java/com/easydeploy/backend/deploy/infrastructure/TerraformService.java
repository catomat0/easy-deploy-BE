package com.easydeploy.backend.deploy.infrastructure;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

@Slf4j
@Service
public class TerraformService {

    private static final Path JOBS_BASE_DIR =
            Paths.get(System.getProperty("user.home"), ".easydeploy", "jobs");

    private static final List<String> TEMPLATES = List.of("main.tf", "variables.tf", "outputs.tf");

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 워크스페이스 ──────────────────────────────────────────────────────────

    public Path setupWorkspace(String jobId, Map<String, Object> tfvars) throws IOException {
        Path jobDir = JOBS_BASE_DIR.resolve(jobId);
        Files.createDirectories(jobDir);
        copyTemplates(jobDir);
        objectMapper.writeValue(jobDir.resolve("terraform.tfvars.json").toFile(), tfvars);
        log.info("[Job {}] Terraform 워크스페이스 초기화: {}", jobId, jobDir);
        return jobDir;
    }

    public Path getJobDir(String jobId) {
        return JOBS_BASE_DIR.resolve(jobId);
    }

    private void copyTemplates(Path jobDir) throws IOException {
        for (String name : TEMPLATES) {
            try (InputStream is = getClass().getResourceAsStream("/terraform/" + name)) {
                if (is == null) throw new IOException("Terraform 템플릿을 찾을 수 없습니다: " + name);
                Files.copy(is, jobDir.resolve(name), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    public List<Map<String, String>> readTerraformFiles(Path jobDir) throws IOException {
        List<Map<String, String>> result = new ArrayList<>();
        for (String name : TEMPLATES) {
            String content;
            Path file = jobDir.resolve(name);
            if (Files.exists(file)) {
                content = Files.readString(file);
            } else {
                try (InputStream is = getClass().getResourceAsStream("/terraform/" + name)) {
                    content = (is != null) ? new String(is.readAllBytes()) : "";
                }
            }
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", name);
            entry.put("content", content);
            result.add(entry);
        }
        return result;
    }

    // ── Terraform 명령 ────────────────────────────────────────────────────────

    public void init(Path jobDir) throws IOException, InterruptedException {
        log.info("terraform init: {}", jobDir);
        int code = runBlocking(jobDir, List.of("terraform", "init", "-no-color", "-input=false"));
        if (code != 0) throw new RuntimeException("terraform init 실패");
        log.info("terraform init 완료");
    }

    public void apply(Path jobDir, Consumer<String> callback) throws IOException, InterruptedException {
        log.info("terraform apply: {}", jobDir);
        streamProcess(jobDir,
                List.of("terraform", "apply", "-auto-approve", "-json", "-no-color", "-input=false"),
                callback);
        log.info("terraform apply 완료");
    }

    public Map<String, String> output(Path jobDir) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder("terraform", "output", "-json")
                .directory(jobDir.toFile())
                .redirectErrorStream(true);
        Process proc = pb.start();
        String raw = new String(proc.getInputStream().readAllBytes());
        int code = proc.waitFor();
        if (code != 0) throw new RuntimeException("terraform output 실패:\n" + raw);

        Map<String, String> result = new HashMap<>();
        JsonNode root = objectMapper.readTree(raw);
        root.fields().forEachRemaining(entry ->
                result.put(entry.getKey(), entry.getValue().path("value").asText("")));
        return result;
    }

    /** apply 완료 후 tfvars.json에서 민감 정보(user_data 등) 제거 — AWS 키는 destroy에 필요하므로 유지 */
    public void sanitizeTfvars(Path jobDir, Map<String, Object> minimalVars) throws IOException {
        objectMapper.writeValue(jobDir.resolve("terraform.tfvars.json").toFile(), minimalVars);
        log.info("tfvars.json 민감 정보 제거 완료: {}", jobDir);
    }

    public void destroy(Path jobDir, Consumer<String> callback) throws IOException, InterruptedException {
        log.info("terraform destroy: {}", jobDir);
        streamProcess(jobDir,
                List.of("terraform", "destroy", "-auto-approve", "-json", "-no-color", "-input=false"),
                callback);
        log.info("terraform destroy 완료");
        deleteJobDir(jobDir);
    }

    private void deleteJobDir(Path jobDir) {
        try {
            Files.walk(jobDir)
                    .sorted(Comparator.reverseOrder())
                    .forEach(p -> p.toFile().delete());
            log.info("Job 디렉토리 삭제 완료: {}", jobDir);
        } catch (Exception e) {
            log.warn("Job 디렉토리 삭제 실패 (수동 확인 필요): {}", jobDir);
        }
    }

    // ── user_data 스크립트 빌더 (Java Spring / Docker 전용) ──────────────────

    public String buildUserData(String githubRepoUrl, String githubToken, String envProd) {
        // 토큰을 URL에 포함하지 않음 — git credential store에 저장해 .git/config 노출 방지
        String cleanUrl = githubRepoUrl.endsWith(".git")
                ? githubRepoUrl.substring(0, githubRepoUrl.length() - 4)
                : githubRepoUrl;

        String envHeredocDelim = "DEPLOY_MCP_ENV_" + System.currentTimeMillis();

        return "#!/bin/bash\n"
                + "set -e\n"
                + "exec > /var/log/easydeploy.log 2>&1\n\n"
                + "echo \"[1/6] 패키지 설치 중...\"\n"
                + "apt-get update -y\n"
                + "apt-get install -y git curl nginx certbot python3-certbot-nginx docker.io\n"
                + "systemctl start docker && systemctl enable docker\n"
                + "usermod -aG docker ubuntu\n"
                + "chmod 666 /var/run/docker.sock\n"
                + "curl -L \"https://github.com/docker/compose/releases/latest/download/docker-compose-linux-x86_64\" -o /usr/local/bin/docker-compose\n"
                + "chmod +x /usr/local/bin/docker-compose\n"
                + "ln -sf /usr/local/bin/docker-compose /usr/bin/docker-compose\n\n"
                + "echo \"[2/6] nginx 설정 중...\"\n"
                + "cat > /etc/nginx/sites-available/app << 'NGINXEOF'\n"
                + "server {\n"
                + "    listen 80;\n"
                + "    server_name _;\n"
                + "    location / {\n"
                + "        proxy_pass http://localhost:8080;\n"
                + "        proxy_set_header Host $host;\n"
                + "        proxy_set_header X-Real-IP $remote_addr;\n"
                + "    }\n"
                + "}\n"
                + "NGINXEOF\n"
                + "ln -sf /etc/nginx/sites-available/app /etc/nginx/sites-enabled/app\n"
                + "rm -f /etc/nginx/sites-enabled/default\n"
                + "nginx -t && systemctl reload nginx\n\n"
                + "echo \"[3/6] 레포 클론 중...\"\n"
                // git credential store: 토큰을 .git/config 대신 권한 600 파일에 저장
                + "sudo -u ubuntu git config --global credential.helper store\n"
                + "printf 'https://x-access-token:" + githubToken + "@github.com\\n'"
                + " > /home/ubuntu/.git-credentials\n"
                + "chown ubuntu:ubuntu /home/ubuntu/.git-credentials\n"
                + "chmod 600 /home/ubuntu/.git-credentials\n"
                + "cd /home/ubuntu\n"
                + "sudo -u ubuntu git clone " + cleanUrl + " app\n"
                + "chown -R ubuntu:ubuntu app\n"
                + "cd app\n\n"
                + "echo \"[4/6] 환경변수 주입 중...\"\n"
                + "cat > /home/ubuntu/app/.env.prod << '" + envHeredocDelim + "'\n"
                + envProd + "\n"
                + envHeredocDelim + "\n"
                + "cp /home/ubuntu/app/.env.prod /home/ubuntu/app/.env\n"
                + "chown ubuntu:ubuntu /home/ubuntu/app/.env.prod /home/ubuntu/app/.env\n\n"
                + "echo \"[5/6] 앱 실행 중...\"\n"
                + "cd /home/ubuntu/app\n"
                + "if [ -f docker-compose.yml ] || [ -f docker-compose.yaml ]; then\n"
                + "    docker-compose up -d --build\n"
                + "elif [ -f Dockerfile ]; then\n"
                + "    docker build -t app .\n"
                + "    docker run -d --name app -p 8080:8080 --env-file .env --restart unless-stopped app\n"
                + "else\n"
                + "    echo \"지원되지 않는 앱 구성: docker-compose.yml 또는 Dockerfile이 필요합니다.\"\n"
                + "    exit 1\n"
                + "fi\n\n"
                + "echo \"[6/6] DEPLOY_COMPLETE\" >> /var/log/easydeploy.log\n";
    }

    // ── 비동기 destroy (cancelDeploy / terminateServer 에서 호출) ─────────────

    @Async("deployTaskExecutor")
    public void destroyAsync(Path jobDir, String logPrefix) {
        try {
            destroy(jobDir, msg -> log.info("{} {}", logPrefix, msg));
        } catch (Exception e) {
            log.error("{} terraform destroy 실패 (수동 정리 필요): {}", logPrefix, e.getMessage());
        }
    }

    // ── 프로세스 유틸 ─────────────────────────────────────────────────────────

    private void streamProcess(Path workDir, List<String> cmd, Consumer<String> callback)
            throws IOException, InterruptedException {
        Process proc = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(proc.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String msg = parseTerraformLogLine(line);
                if (msg != null && callback != null) callback.accept(msg);
            }
        }

        int code = proc.waitFor();
        if (code != 0) throw new RuntimeException(cmd.get(1) + " 실패 (exit=" + code + ")");
    }

    private int runBlocking(Path workDir, List<String> cmd) throws IOException, InterruptedException {
        Process proc = new ProcessBuilder(cmd)
                .directory(workDir.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(proc.getInputStream().readAllBytes());
        int code = proc.waitFor();
        if (code != 0) log.error("명령 실패 ({}): {}", String.join(" ", cmd), output);
        return code;
    }

    private String parseTerraformLogLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            JsonNode data = objectMapper.readTree(line);
            String type = data.path("type").asText("");
            return switch (type) {
                case "apply_progress" -> {
                    String resource = data.path("hook").path("resource").path("resource_type").asText("");
                    String action = data.path("hook").path("action").asText("");
                    yield resource.isBlank() ? null : "[Terraform] " + resource + " " + action + " 진행 중...";
                }
                case "apply_complete" -> {
                    String resource = data.path("hook").path("resource").path("resource_type").asText("");
                    String action = data.path("hook").path("action").asText("");
                    yield resource.isBlank() ? null : "[Terraform] ✓ " + resource + " " + action + " 완료";
                }
                case "change_summary" -> {
                    int add = data.path("changes").path("add").asInt(0);
                    int change = data.path("changes").path("change").asInt(0);
                    int remove = data.path("changes").path("remove").asInt(0);
                    yield "[Terraform] 변경 요약 — 생성 " + add + " / 수정 " + change + " / 삭제 " + remove;
                }
                case "outputs" -> "[Terraform] 출력값 저장 완료";
                case "diagnostic" -> {
                    String level = data.path("@level").asText("");
                    String msg = data.path("@message").asText("");
                    yield "error".equals(level) && !msg.isBlank() ? "[Terraform 오류] " + msg : null;
                }
                default -> null;
            };
        } catch (Exception e) {
            return line.isBlank() ? null : "[Terraform] " + line;
        }
    }
}
