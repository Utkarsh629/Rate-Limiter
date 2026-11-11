package com.utkarsh.ratelimiter.config;

import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy;

/**
 * Lets the application start while MySQL is unreachable.
 *
 * <p>If the startup migration fails because the database <b>cannot be reached</b>, the migration is
 * deferred: the instance starts NOT READY (no policies) and the policy loader retries the migration
 * before each load attempt. Any other Flyway failure (bad migration, checksum mismatch, validation
 * error) still aborts startup, because retrying cannot fix it.
 */
public class DeferredFlywayMigration implements FlywayMigrationStrategy {

    private static final Logger log = LoggerFactory.getLogger(DeferredFlywayMigration.class);

    private final AtomicBoolean migrated = new AtomicBoolean();
    private volatile Flyway flyway;

    @Override
    public void migrate(Flyway flyway) {
        this.flyway = flyway;
        try {
            flyway.migrate();
            migrated.set(true);
        } catch (FlywayException e) {
            if (!isConnectionFailure(e)) {
                throw e;
            }
            log.warn("Database unreachable at startup; Flyway migration deferred until it is reachable. Cause: {}", e.getMessage());
        }
    }

    /** Runs the deferred migration, if any. Throws if the database is still unreachable. */
    public synchronized void migrateIfPending() {
        if (migrated.get() || flyway == null) {
            return;
        }
        flyway.migrate();
        migrated.set(true);
        log.info("Deferred Flyway migration completed");
    }

    public boolean isMigrated() {
        return migrated.get();
    }

    public static boolean isConnectionFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof SQLTransientConnectionException || t instanceof ConnectException) {
                return true;
            }
            if (t instanceof SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08")) {
                return true; // SQLState class 08 = connection exception
            }
        }
        return false;
    }
}
