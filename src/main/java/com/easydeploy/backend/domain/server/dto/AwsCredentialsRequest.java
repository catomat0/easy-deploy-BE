package com.easydeploy.backend.domain.server.dto;

import lombok.Getter;

@Getter
public class AwsCredentialsRequest {
    private String awsAccessKeyId;
    private String awsSecretAccessKey;
    private String awsRegion;
    private String vpcId;
}
