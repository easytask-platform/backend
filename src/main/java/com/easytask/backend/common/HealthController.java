package com.easytask.backend.common;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Public liveness endpoint for platform health checks and keep-alive pings
 * (Render health check + UptimeRobot warming against free-tier spin-down).
 * Intentionally unauthenticated and cheap: no DB access.
 */
@RestController
public class HealthController {

    @GetMapping("/api/v1/health")
    public Map<String, String> health() {
        return Map.of("status", "UP");
    }
}
