package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.utkarsh.ratelimiter.config.DeferredFlywayMigration;
import com.utkarsh.ratelimiter.policy.InvalidPolicyException;
import com.utkarsh.ratelimiter.policy.PolicyConflictException;
import com.utkarsh.ratelimiter.policy.PolicyStore;
import com.utkarsh.ratelimiter.policy.RateLimitPolicy;
import java.net.ConnectException;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.UncategorizedSQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/** MySQL error-code mapping and the startup migration deferral decision. */
class PersistenceErrorTranslationTest {

    private final RateLimitPolicy policy = new RateLimitPolicy();

    PersistenceErrorTranslationTest() {
        policy.setName("free-tier");
    }

    @Test
    void checkViolation3819BecomesInvalidPolicyNamingTheField() {
        SQLException sql = new SQLException("Check constraint 'chk_policy_window' is violated.", "HY000", 3819);
        RuntimeException translated = PolicyStore.translate(new UncategorizedSQLException("insert", "INSERT ...", sql), policy);

        assertThat(translated).isInstanceOf(InvalidPolicyException.class)
                .hasMessageContaining("chk_policy_window");
        assertThat(((InvalidPolicyException) translated).field()).isEqualTo("windowMs");
    }

    @Test
    void duplicateEntry1062BecomesConflict() {
        SQLException sql = new SQLException("Duplicate entry 'free-tier' for key 'uk_rate_limit_policy_name'", "23000", 1062);
        assertThat(PolicyStore.translate(new DataIntegrityViolationException("dup", sql), policy))
                .isInstanceOf(PolicyConflictException.class).hasMessageContaining("free-tier");
    }

    @Test
    void otherErrorsAreRethrownUnchanged() {
        DataIntegrityViolationException other = new DataIntegrityViolationException("x", new SQLException("fk", "23000", 1452));
        assertThat(PolicyStore.translate(other, policy)).isSameAs(other);
    }

    @Test
    void recognisesConnectionFailures() {
        assertThat(DeferredFlywayMigration.isConnectionFailure(new FlywayException("x", new SQLException("down", "08S01")))).isTrue();
        assertThat(DeferredFlywayMigration.isConnectionFailure(new FlywayException("x", new SQLTransientConnectionException("pool")))).isTrue();
        assertThat(DeferredFlywayMigration.isConnectionFailure(new FlywayException("x", new ConnectException("refused")))).isTrue();
        assertThat(DeferredFlywayMigration.isConnectionFailure(new FlywayException("Validate failed: checksum mismatch"))).isFalse();
        assertThat(DeferredFlywayMigration.isConnectionFailure(new FlywayException("x", new SQLException("syntax", "42000")))).isFalse();
    }

    @Test
    void unreachableDatabaseDefersMigrationUntilRetry() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate())
                .thenThrow(new FlywayException("x", new SQLException("down", "08S01")))
                .thenReturn(null);
        DeferredFlywayMigration migration = new DeferredFlywayMigration();

        migration.migrate(flyway);          // startup: deferred, no exception
        assertThat(migration.isMigrated()).isFalse();

        migration.migrateIfPending();        // first policy load after recovery
        assertThat(migration.isMigrated()).isTrue();
        migration.migrateIfPending();        // no further attempts
        verify(flyway, times(2)).migrate();
    }

    @Test
    void brokenMigrationsStillFailStartup() {
        Flyway flyway = mock(Flyway.class);
        when(flyway.migrate()).thenThrow(new FlywayException("Validate failed: checksum mismatch"));

        assertThatThrownBy(() -> new DeferredFlywayMigration().migrate(flyway)).isInstanceOf(FlywayException.class);
    }
}
