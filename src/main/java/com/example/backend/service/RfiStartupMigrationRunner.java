package com.example.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import com.example.backend.repository.RfiRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs once at startup (every start, but it is idempotent and costs one tiny query each):
 *
 *  1. Re-encrypts old Gmail secrets/tokens (hardcoded AES/ECB key) with AES-256-GCM.
 *  2. Trims whitespace from rfis.sent_by, so the reply poll can use the index
 *     (it used to need TRIM(sent_by) = ?, which cannot use an index).
 *
 * Failures are logged and never stop the application from starting.
 * Switch off with  rfi.migrations.on-startup=false
 */
@Component
@Slf4j
public class RfiStartupMigrationRunner implements ApplicationRunner {

    @Autowired
    private RfiSettingsService rfiSettingsService;

    @Autowired
    private RfiRepository rfiRepository;

    @Value("${rfi.migrations.on-startup:true}")
    private boolean enabled = true;

    @Override
    public void run(ApplicationArguments args) {
        if (!enabled) {
            return;
        }
        try {
            rfiSettingsService.reencryptLegacySecrets();
        } catch (Exception e) {
            log.error("RFI secret re-encryption failed: {}", e.getClass().getSimpleName());
        }
        try {
            int trimmed = rfiRepository.trimSentBy();
            if (trimmed > 0) {
                log.info("Trimmed whitespace from sent_by on {} RFI row(s)", trimmed);
            }
        } catch (Exception e) {
            log.warn("sent_by trim skipped: {}", e.getClass().getSimpleName());
        }
    }
}