package org.program.pair.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.program.pair.seed.DemoDataSeeder;
import org.program.pair.seed.ResetDemoDataCommand;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Admin controller for seed data management.
 * This controller is ONLY available in dev and staging environments.
 *
 * SECURITY NOTE: In production, this controller does not exist due to @Profile annotation.
 * Even if somehow accessed, ResetDemoDataCommand has additional safety checks.
 */
@RestController
@RequestMapping("/api/admin/seed")
@Profile({"dev", "staging"})
@RequiredArgsConstructor
@Slf4j
public class AdminSeedController {

    private final ResetDemoDataCommand resetCommand;
    private final DemoDataSeeder demoDataSeeder;

    /**
     * Reset and recreate demo data.
     * This endpoint:
     * 1. Deletes all existing demo users and their data
     * 2. Recreates fresh demo data
     *
     * POST /api/admin/seed/demo/reset
     *
     * @return ResponseEntity with success message and summary
     */
    @PostMapping("/demo/reset")
    public ResponseEntity<Map<String, Object>> resetDemoData() {
        log.info("Admin endpoint called: /api/admin/seed/demo/reset");

        try {
            // Step 1: Delete existing demo data
            resetCommand.resetDemoData();

            // Step 2: Recreate demo data
            log.info("Recreating demo data...");
            demoDataSeeder.run();

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Demo data has been successfully reset and recreated",
                "timestamp", System.currentTimeMillis()
            ));

        } catch (Exception e) {
            log.error("Failed to reset demo data: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", false,
                "error", e.getMessage(),
                "timestamp", System.currentTimeMillis()
            ));
        }
    }

    /**
     * Health check endpoint to verify admin controller is available.
     *
     * GET /api/admin/seed/status
     *
     * @return ResponseEntity with status information
     */
    @PostMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        return ResponseEntity.ok(Map.of(
            "available", true,
            "message", "Admin seed controller is active",
            "profile", System.getProperty("spring.profiles.active", "default")
        ));
    }
}
