package com.example.backend.controller;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Unauthenticated keep-alive/health endpoint.
 *
 * Hit by an external cron (see .github/workflows/keep-alive.yml) every ~10-14
 * minutes so Render's free tier never sits idle long enough to spin down,
 * and so the Hikari pool to Railway MySQL never sits idle long enough to
 * get dropped either. Deliberately cheap: one "SELECT 1", nothing else.
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    @Autowired
    private DataSource dataSource;

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("timestamp", Instant.now().toString());

        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            stmt.execute("SELECT 1");
            body.put("database", "UP");
            return ResponseEntity.ok(body);
        } catch (Exception e) {
            // Server itself is up (we got this far); DB is the problem.
            // Still return 200 so the pinger doesn't flag the whole service
            // down over a transient DB hiccup — just report it in the body.
            body.put("database", "DOWN");
            body.put("databaseError", e.getMessage());
            return ResponseEntity.status(HttpStatus.OK).body(body);
        }
    }
}