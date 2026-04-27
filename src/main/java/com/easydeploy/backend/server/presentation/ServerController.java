package com.easydeploy.backend.server.presentation;

import com.easydeploy.backend.server.application.ServerService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/servers")
@RequiredArgsConstructor
public class ServerController {

    private final ServerService serverService;

    @GetMapping
    public ResponseEntity<List<ServerResponse>> getMyServers(@AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(serverService.getMyServers(userEmail));
    }

    @GetMapping("/running")
    public ResponseEntity<List<ServerResponse>> getRunningServers(@AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(serverService.getRunningServers(userEmail));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ServerResponse> terminateServer(
            @PathVariable Long id,
            @AuthenticationPrincipal String userEmail) {
        return ResponseEntity.ok(serverService.terminateServer(id, userEmail));
    }
}
