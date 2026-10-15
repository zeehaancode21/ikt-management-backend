// ReplyForeignKeyFixer.java
package com.example.backend.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * Repairs the "reply to a message" link in the database so deleting messages,
 * conversations, groups and employees no longer fails.
 *
 * The problem: messages and group_messages each have a reply_to_id column that
 * points at another message in the same table. In your existing database that
 * link is set to "block deletes" (RESTRICT), so deleting a message that another
 * message replies to fails with:
 *   "Cannot delete or update a parent row: a foreign key constraint fails".
 * The entity classes ask for "ON DELETE SET NULL" (a deleted message just
 * leaves its replies without a quote), but Hibernate never changes a link that
 * already exists in the database, so the old setting stayed.
 *
 * OPT-IN: this class is only created when APP_MAINTENANCE_FIX_REPLY_FK=true
 * (property app.maintenance.fix-reply-fk=true). Your database is already repaired,
 * so by default it no longer runs and costs no DB queries on cold starts. Turn it on
 * for one deploy only if you ever restore/recreate the database.
 *
 * When enabled, this class runs once every time the app starts, looks at those two links,
 * and changes any that are not already "SET NULL". If they are already
 * correct it does nothing, so it is safe to leave in permanently. Any problem
 * is only written to the log; it never stops the app from starting.
 */
@Component
@ConditionalOnProperty(name = "app.maintenance.fix-reply-fk", havingValue = "true")
public class ReplyForeignKeyFixer {

    private static final Logger log = LoggerFactory.getLogger(ReplyForeignKeyFixer.class);

    // table, column
    private static final String[][] TARGETS = {
            {"messages", "reply_to_id"},
            {"group_messages", "reply_to_id"}
    };

    private static final String FIND_FOREIGN_KEYS =
            "SELECT k.CONSTRAINT_NAME, k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME, r.DELETE_RULE "
          + "FROM information_schema.KEY_COLUMN_USAGE k "
          + "JOIN information_schema.REFERENTIAL_CONSTRAINTS r "
          + "  ON r.CONSTRAINT_SCHEMA = k.CONSTRAINT_SCHEMA "
          + " AND r.CONSTRAINT_NAME = k.CONSTRAINT_NAME "
          + " AND r.TABLE_NAME = k.TABLE_NAME "
          + "WHERE k.TABLE_SCHEMA = DATABASE() "
          + "  AND k.TABLE_NAME = ? "
          + "  AND k.COLUMN_NAME = ? "
          + "  AND k.REFERENCED_TABLE_NAME IS NOT NULL";

    @Autowired
    private DataSource dataSource;

    @EventListener(ApplicationReadyEvent.class)
    public void fixReplyForeignKeys() {
        for (String[] target : TARGETS) {
            try {
                fixOne(target[0], target[1]);
            } catch (Exception e) {
                log.warn("Could not check/repair the reply link on {}.{}: {}",
                        target[0], target[1], e.getMessage());
            }
        }
    }

    private static final class ForeignKey {
        final String name;
        final String referencedTable;
        final String referencedColumn;

        ForeignKey(String name, String referencedTable, String referencedColumn) {
            this.name = name;
            this.referencedTable = referencedTable;
            this.referencedColumn = referencedColumn;
        }
    }

    private void fixOne(String table, String column) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {

            List<ForeignKey> needsFixing = new ArrayList<>();

            try (PreparedStatement ps = conn.prepareStatement(FIND_FOREIGN_KEYS)) {
                ps.setString(1, table);
                ps.setString(2, column);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        String deleteRule = rs.getString("DELETE_RULE");
                        if (!"SET NULL".equalsIgnoreCase(deleteRule)) {
                            needsFixing.add(new ForeignKey(
                                    rs.getString("CONSTRAINT_NAME"),
                                    rs.getString("REFERENCED_TABLE_NAME"),
                                    rs.getString("REFERENCED_COLUMN_NAME")));
                        }
                    }
                }
            }

            if (needsFixing.isEmpty()) {
                log.info("Reply link on {}.{} is already correct.", table, column);
                return;
            }

            for (ForeignKey fk : needsFixing) {
                try (Statement st = conn.createStatement()) {
                    String dropSql = "ALTER TABLE `" + table + "` DROP FOREIGN KEY `" + fk.name + "`";
                    String addSql = "ALTER TABLE `" + table + "` ADD CONSTRAINT `" + fk.name + "` "
                            + "FOREIGN KEY (`" + column + "`) "
                            + "REFERENCES `" + fk.referencedTable + "` (`" + fk.referencedColumn + "`) "
                            + "ON DELETE SET NULL";

                    st.execute(dropSql);
                    try {
                        st.execute(addSql);
                        log.info("Repaired reply link {} on {}.{} (deleting a message now clears "
                                + "its replies' quote instead of failing).", fk.name, table, column);
                    } catch (SQLException addFailed) {
                        // Put the original link back so the table is never left without one.
                        try {
                            st.execute("ALTER TABLE `" + table + "` ADD CONSTRAINT `" + fk.name + "` "
                                    + "FOREIGN KEY (`" + column + "`) "
                                    + "REFERENCES `" + fk.referencedTable + "` (`" + fk.referencedColumn + "`)");
                        } catch (SQLException restoreFailed) {
                            log.error("Could not restore reply link {} on {}.{}: {}",
                                    fk.name, table, column, restoreFailed.getMessage());
                        }
                        throw addFailed;
                    }
                }
            }
        }
    }
}