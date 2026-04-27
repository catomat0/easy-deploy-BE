package com.easydeploy.backend.domain.server.controller;

import com.easydeploy.backend.domain.server.dto.AwsCredentialsRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.ec2.model.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/aws")
@RequiredArgsConstructor
public class AwsResourceController {

    @PostMapping("/vpcs")
    public ResponseEntity<List<Map<String, String>>> listVpcs(@RequestBody AwsCredentialsRequest req) {
        try (Ec2Client ec2 = buildEc2Client(req)) {
            List<Map<String, String>> vpcs = ec2.describeVpcs().vpcs().stream()
                    .map(vpc -> Map.of(
                            "vpcId", vpc.vpcId(),
                            "cidr", vpc.cidrBlock(),
                            "isDefault", String.valueOf(vpc.isDefault()),
                            "name", vpc.tags().stream()
                                    .filter(t -> "Name".equals(t.key()))
                                    .map(Tag::value)
                                    .findFirst()
                                    .orElse("")
                    ))
                    .toList();
            return ResponseEntity.ok(vpcs);
        }
    }

    @PostMapping("/subnets")
    public ResponseEntity<List<Map<String, String>>> listSubnets(@RequestBody AwsCredentialsRequest req) {
        try (Ec2Client ec2 = buildEc2Client(req)) {
            DescribeSubnetsRequest subnetsReq = DescribeSubnetsRequest.builder()
                    .filters(Filter.builder().name("vpc-id").values(req.getVpcId()).build())
                    .build();
            List<Map<String, String>> subnets = ec2.describeSubnets(subnetsReq).subnets().stream()
                    .map(subnet -> Map.of(
                            "subnetId", subnet.subnetId(),
                            "cidr", subnet.cidrBlock(),
                            "az", subnet.availabilityZone(),
                            "name", subnet.tags().stream()
                                    .filter(t -> "Name".equals(t.key()))
                                    .map(Tag::value)
                                    .findFirst()
                                    .orElse("")
                    ))
                    .toList();
            return ResponseEntity.ok(subnets);
        }
    }

    private Ec2Client buildEc2Client(AwsCredentialsRequest req) {
        return Ec2Client.builder()
                .region(Region.of(req.getAwsRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(req.getAwsAccessKeyId(), req.getAwsSecretAccessKey())))
                .build();
    }
}
