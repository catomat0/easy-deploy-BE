package com.easydeploy.backend.server.presentation;

import lombok.Getter;

@Getter
public class AwsCredentialsRequest {
    private String awsAccessKeyId;
    private String awsSecretAccessKey;
    private String awsRegion;
    private String vpcId;
}
