package com.easydeploy.backend.server.domain;

import jakarta.persistence.*;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Getter
@NoArgsConstructor
public class AwsCredential {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private Long serverId;

    @Column(length = 512)
    private String encryptedAccessKeyId;

    @Column(length = 512)
    private String encryptedSecretAccessKey;

    @Column(columnDefinition = "TEXT")
    private String encryptedSshPrivateKey;

    private String awsRegion;
    private LocalDateTime createdAt;

    @Builder
    public AwsCredential(Long serverId, String encryptedAccessKeyId,
                         String encryptedSecretAccessKey, String encryptedSshPrivateKey,
                         String awsRegion) {
        this.serverId = serverId;
        this.encryptedAccessKeyId = encryptedAccessKeyId;
        this.encryptedSecretAccessKey = encryptedSecretAccessKey;
        this.encryptedSshPrivateKey = encryptedSshPrivateKey;
        this.awsRegion = awsRegion;
        this.createdAt = LocalDateTime.now();
    }
}
