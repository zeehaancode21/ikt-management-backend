package com.example.backend.service;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import com.example.backend.repository.RfiRepository;

import lombok.extern.slf4j.Slf4j;

/**
 * Runs once at startup (every start, but it is idempotent and costs one tiny query each):
 *
 *  1. Re-encrypts old Gmail secrets/tokens (hardcoded AES/ECB key) with AES-256-GCM.
 *  2. Trims whitespace from rfis.sent_by, so the reply poll can use the index
 *     (it used to need TRIM(sent_by) = ?, which cannot use an index).
 *  3. Turns the RFI status columns from a MySQL ENUM(...) into VARCHAR(32), but only if they
 *     still are an ENUM. Hibernate (ddl-auto=update) creates them as ENUM and never adds new
 *     values to it, so saving OPEN / INTERNAL_APPROVED / DRAFTING would fail with
 *     "Data truncated for column 'status'". As VARCHAR, every future status just works.
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

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        try {
            widenStatusColumns();
        } catch (Exception e) {
            log.error("RFI status column migration failed - new statuses cannot be saved until it is applied: {}",
                    e.toString());
        }
    }

    /** rfis.status, rfi_status_history.old_status / new_status: ENUM -> VARCHAR(32), keeping NULL-ability. */
    private void widenStatusColumns() {
        var columns = jdbcTemplate.queryForList(
                "SELECT TABLE_NAME AS t, COLUMN_NAME AS c, COLUMN_TYPE AS ty, IS_NULLABLE AS n "
                        + "FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND ("
                        + "(TABLE_NAME = 'rfis' AND COLUMN_NAME = 'status') OR "
                        + "(TABLE_NAME = 'rfi_status_history' AND COLUMN_NAME IN ('old_status', 'new_status')))");
        for (var col : columns) {
            String type = String.valueOf(col.get("ty")).toLowerCase();
            if (!type.startsWith("enum")) {
                continue;   // already VARCHAR (or a fresh schema): nothing to do
            }
            String table = String.valueOf(col.get("t"));
            String column = String.valueOf(col.get("c"));
            boolean nullable = "YES".equalsIgnoreCase(String.valueOf(col.get("n")));
            // Names come from the query above (fixed literals), never from user input.
            jdbcTemplate.execute("ALTER TABLE `" + table + "` MODIFY COLUMN `" + column + "` VARCHAR(32)"
                    + (nullable ? " NULL" : " NOT NULL"));
            log.info("Changed {}.{} from ENUM to VARCHAR(32) so the internal-workflow statuses can be saved",
                    table, column);
        }
    }
}