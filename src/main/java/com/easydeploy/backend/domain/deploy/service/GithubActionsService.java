package com.easydeploy.backend.deploy.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.goterl.lazysodium.LazySodium;
import com.goterl.lazysodium.LazySodiumJava;
import com.goterl.lazysodium.SodiumJava;
import com.goterl.lazysodium.utils.Key;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Base64;
import java.util.Map;

@Slf4j
@Service
public class GithubActionsService {

    private static final String GITHUB_API = "https://api.github.com";

    private static final String DEPLOY_WORKFLOW = """
            name: Deploy to EC2

            on:
              push:
                branches: [main]

            jobs:
              deploy:
                runs-on: ubuntu-latest

                steps:
                  - name: Checkout
                    uses: actions/checkout@v4

                  - name: Deploy to EC2 via SSH
                    uses: appleboy/ssh-action@v1.0.3
                    with:
                      host: ${{ secrets.EC2_HOST }}
                      username: ubuntu
                      key: ${{ secrets.EC2_SSH_KEY }}
                      command_timeout: 20m
                      script: |
                        set -e
                        cd /home/ubuntu/app
                        git pull origin main
                        echo "${{ secrets.ENV_PROD }}" > .env.prod
                        cp .env.prod .env
                        if ! docker info > /dev/null 2>&1; then sudo chmod 666 /var/run/docker.sock; fi
                        if [ -f docker-compose.yml ] || [ -f docker-compose.yaml ]; then
                          docker-compose up -d --build --remove-orphans
                          docker image prune -f
                        elif [ -f Dockerfile ]; then
                          docker build -t app .
                          docker stop app 2>/dev/null || true
                          docker rm app 2>/dev/null || true
                          docker run -d --name app -p 8080:8080 --env-file .env --restart unless-stopped app
                          docker image prune -f
                        else
                          echo "지원되지 않는 앱 구성: docker-compose.yml 또는 Dockerfile이 필요합니다."
                          exit 1
                        fi

                  - name: Health Check
                    run: |
                      sleep 15
                      STATUS=$(curl -s -o /dev/null -w "%{http_code}" http://${{ secrets.EC2_HOST }}:80 || echo "000")
                      echo "Health check: HTTP $STATUS"
            """;

    private final HttpClient httpClient = HttpClient.newHttpClient();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LazySodiumJava lazySodium = new LazySodiumJava(new SodiumJava());

    public void setup(String githubToken, String githubRepoUrl,
                      String ec2Host, String ec2SshKey, String envProd) throws Exception {
        String repoFullName = parseRepoFullName(githubRepoUrl);
        log.info("GitHub Actions CD 설정 시작: {}", repoFullName);

        registerSecret(githubToken, repoFullName, "EC2_HOST", ec2Host);
        registerSecret(githubToken, repoFullName, "EC2_SSH_KEY", ec2SshKey);
        registerSecret(githubToken, repoFullName, "ENV_PROD", envProd);

        upsertWorkflowFile(githubToken, repoFullName);
        log.info("GitHub Actions CD 설정 완료: {}", repoFullName);
    }

    private void registerSecret(String token, String repoFullName,
                                 String secretName, String secretValue) throws Exception {
        String pubKeyJson = get(token, "/repos/" + repoFullName + "/actions/secrets/public-key");
        JsonNode keyNode = objectMapper.readTree(pubKeyJson);
        String keyId = keyNode.path("key_id").asText();
        String pubKeyBase64 = keyNode.path("key").asText();

        String encrypted = encryptSecret(pubKeyBase64, secretValue);

        String body = objectMapper.writeValueAsString(
                Map.of("encrypted_value", encrypted, "key_id", keyId));
        put(token, "/repos/" + repoFullName + "/actions/secrets/" + secretName, body);
        log.info("GitHub Secret 등록 완료: {}", secretName);
    }

    private String encryptSecret(String pubKeyBase64, String secretValue) throws Exception {
        byte[] pubKeyBytes = Base64.getDecoder().decode(pubKeyBase64);
        Key recipientKey = Key.fromBytes(pubKeyBytes);
        String encryptedHex = lazySodium.cryptoBoxSealEasy(secretValue, recipientKey);
        byte[] encryptedBytes = LazySodium.toBin(encryptedHex);
        return Base64.getEncoder().encodeToString(encryptedBytes);
    }

    private void upsertWorkflowFile(String token, String repoFullName) throws Exception {
        String path = ".github/workflows/deploy.yml";
        String contentBase64 = Base64.getEncoder().encodeToString(DEPLOY_WORKFLOW.getBytes());

        String sha = null;
        try {
            String existing = get(token, "/repos/" + repoFullName + "/contents/" + path);
            sha = objectMapper.readTree(existing).path("sha").asText(null);
        } catch (Exception ignored) {}

        Map<String, Object> bodyMap = new java.util.LinkedHashMap<>();
        bodyMap.put("message", sha != null
                ? "chore: update CD pipeline (easydeploy)"
                : "chore: add CD pipeline (easydeploy)");
        bodyMap.put("content", contentBase64);
        if (sha != null) bodyMap.put("sha", sha);

        put(token, "/repos/" + repoFullName + "/contents/" + path,
                objectMapper.writeValueAsString(bodyMap));
        log.info("GitHub Actions 워크플로우 파일 {} 완료", sha != null ? "업데이트" : "생성");
    }

    private String parseRepoFullName(String repoUrl) {
        String url = repoUrl.replaceAll("\\.git$", "").replaceAll("/$", "");
        int idx = url.indexOf("github.com/");
        if (idx < 0) throw new IllegalArgumentException("올바르지 않은 GitHub URL: " + repoUrl);
        return url.substring(idx + "github.com/".length());
    }

    private String get(String token, String path) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .GET()
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300)
            throw new RuntimeException("GitHub API 오류 [GET " + path + "]: " + res.statusCode());
        return res.body();
    }

    private void put(String token, String path, String body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(GITHUB_API + path))
                .header("Authorization", "Bearer " + token)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body))
                .build();
        HttpResponse<String> res = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 300)
            throw new RuntimeException("GitHub API 오류 [PUT " + path + "]: " + res.statusCode());
    }
}
