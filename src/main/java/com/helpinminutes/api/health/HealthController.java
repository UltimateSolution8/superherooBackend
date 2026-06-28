package com.helpinminutes.api.health;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class HealthController {
    @GetMapping({"/health", "/api/v1/health"})
    public ResponseEntity<String> health() {
        // simple endpoint to satisfy load‑balancer probes
        return ResponseEntity.ok("ok");
    }
}
