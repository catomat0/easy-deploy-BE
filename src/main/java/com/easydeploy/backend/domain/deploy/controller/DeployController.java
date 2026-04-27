package com.easydeploy.backend.domain.deploy.controller;

import com.easydeploy.backend.domain.deploy.dto.DeployRequest;
import com.easydeploy.backend.domain.deploy.dto.DeployResponse;
import com.easydeploy.backend.domain.deploy.service.DeployService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@RequestMapping("/deploy")
@RequiredArgsConstructor
public class DeployController {

    private final DeployService deployService;

    @PostMapping
    public ResponseEntity<DeployResponse> startDeploy(
            @Valid @RequestBody DeployRequest request,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.accepted().body(deployService.startDeploy(request, userEmail));
    }

    @GetMapping("/{uuid}")
    public ResponseEntity<DeployResponse> getStatus(@PathVariable String uuid) {
        return ResponseEntity.ok(deployService.getStatus(uuid));
    }

    @GetMapping(value = "/{uuid}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamDeploy(@PathVariable String uuid) {
        return deployService.createSseEmitter(uuid);
    }

    @DeleteMapping("/{uuid}")
    public ResponseEntity<DeployResponse> cancelDeploy(
            @PathVariable String uuid,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(deployService.cancelDeploy(uuid, userEmail));
    }

    @GetMapping("/{uuid}/terraform")
    public ResponseEntity<?> getTerraformFiles(
            @PathVariable String uuid,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(deployService.getTerraformFiles(uuid, userEmail));
    }
}
