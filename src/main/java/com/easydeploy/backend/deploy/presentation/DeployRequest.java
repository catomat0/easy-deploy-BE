package com.easydeploy.backend.deploy.presentation;

import jakarta.validation.constraints.NotBlank;
import lombok.Getter;

@Getter
public class DeployRequest {

    @NotBlank(message = "AWS Access Key ID는 필수입니다.")
    private String awsAccessKeyId;

    @NotBlank(message = "AWS Secret Access Key는 필수입니다.")
    private String awsSecretAccessKey;

    @NotBlank(message = "AWS 리전은 필수입니다.")
    private String awsRegion;

    @NotBlank(message = "인스턴스 타입은 필수입니다.")
    private String instanceType;

    @NotBlank(message = "GitHub 레포 URL은 필수입니다.")
    private String githubRepoUrl;

    @NotBlank(message = "GitHub Token은 필수입니다.")
    private String githubToken;

    @NotBlank(message = ".env.prod 내용은 필수입니다.")
    private String envProd;

    private String vpcId;
    private String subnetId;

    private int storageSize = 20;
}
