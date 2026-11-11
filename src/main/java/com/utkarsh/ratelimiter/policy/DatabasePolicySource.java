package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.config.DeferredFlywayMigration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Reads enabled policies from MySQL, first running migrations that were deferred at startup. */
@Component
public class DatabasePolicySource implements PolicySource {

    private static final Logger log = LoggerFactory.getLogger(DatabasePolicySource.class);

    private final PolicyRepository repository;
    private final TransactionTemplate readOnly;
    private final DeferredFlywayMigration migration;

    public DatabasePolicySource(PolicyRepository repository, TransactionTemplate transactionTemplate,
                                DeferredFlywayMigration migration) {
        this.repository = repository;
        this.readOnly = new TransactionTemplate(transactionTemplate.getTransactionManager());
        this.readOnly.setReadOnly(true);
        this.migration = migration;
    }

    @Override
    public Loaded load() {
        migration.migrateIfPending();
        List<RateLimitPolicy> rows = readOnly.execute(status -> repository.findAllByEnabledTrue());
        List<ResolvedPolicy> compiled = new ArrayList<>(rows.size());
        int skipped = 0;
        for (RateLimitPolicy row : rows) {
            try {
                compiled.add(row.toResolved());
            } catch (InvalidPolicyException e) {
                skipped++;
                log.error("Skipping policy {} ('{}'): {} {}", row.getId(), row.getName(), e.field(), e.getMessage());
            }
        }
        return new Loaded(compiled, skipped);
    }
}
