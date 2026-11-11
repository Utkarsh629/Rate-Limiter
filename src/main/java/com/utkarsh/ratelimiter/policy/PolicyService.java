package com.utkarsh.ratelimiter.policy;

import com.utkarsh.ratelimiter.policy.api.PolicyRequest;
import com.utkarsh.ratelimiter.policy.api.PolicyResponse;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Policy administration. Every successful write publishes a {@link PolicyChangedEvent}, which is
 * turned into a cross-instance invalidation after the transaction commits.
 */
@Service
public class PolicyService {

    private final PolicyRepository repository;
    private final PolicyStore store;
    private final ApplicationEventPublisher events;

    public PolicyService(PolicyRepository repository, PolicyStore store, ApplicationEventPublisher events) {
        this.repository = repository;
        this.store = store;
        this.events = events;
    }

    @Transactional
    public PolicyResponse create(PolicyRequest request) {
        RateLimitPolicy policy = new RateLimitPolicy();
        apply(request, policy);
        RateLimitPolicy saved = store.save(policy);
        events.publishEvent(new PolicyChangedEvent(saved.getId(), PolicyChangedEvent.Operation.CREATED));
        return PolicyResponse.from(saved);
    }

    @Transactional(readOnly = true)
    public PolicyResponse get(long id) {
        return PolicyResponse.from(find(id));
    }

    @Transactional(readOnly = true)
    public Page<PolicyResponse> list(Pageable pageable) {
        return repository.findAll(pageable).map(PolicyResponse::from);
    }

    @Transactional
    public PolicyResponse update(long id, PolicyRequest request) {
        if (request.version() == null) {
            throw new InvalidPolicyException("version", "version is required for updates");
        }
        RateLimitPolicy policy = find(id);
        if (policy.getVersion() != request.version()) {
            throw new PolicyConflictException("Policy " + id + " was modified (current version "
                    + policy.getVersion() + ", request version " + request.version() + ")");
        }
        apply(request, policy);
        RateLimitPolicy saved;
        try {
            saved = store.save(policy);
        } catch (ObjectOptimisticLockingFailureException e) {
            throw new PolicyConflictException("Policy " + id + " was modified concurrently");
        }
        events.publishEvent(new PolicyChangedEvent(id, PolicyChangedEvent.Operation.UPDATED));
        return PolicyResponse.from(saved);
    }

    @Transactional
    public void delete(long id) {
        repository.delete(find(id));
        repository.flush();
        events.publishEvent(new PolicyChangedEvent(id, PolicyChangedEvent.Operation.DELETED));
    }

    private RateLimitPolicy find(long id) {
        return repository.findById(id).orElseThrow(() -> new PolicyNotFoundException(id));
    }

    /** Copies the request onto the entity after the checks Bean Validation cannot express. */
    private static void apply(PolicyRequest r, RateLimitPolicy p) {
        ResolvedPolicy.parsePattern(r.endpointPattern()); // rejects patterns Spring cannot parse
        p.setName(r.name());
        p.setDescription(r.description());
        p.setHttpMethod(r.httpMethod());
        p.setEndpointPattern(r.endpointPattern());
        p.setTier(r.tier());
        p.setKeyBy(r.keyBy());
        p.setAlgorithm(r.algorithm());
        p.setLimitValue(r.limit());
        p.setWindowMs(r.windowMs());
        p.setRefillTokens(r.refillTokens());
        p.setFailureMode(r.failureMode());
        p.setPriority(r.priority() == null ? 0 : r.priority());
        p.setEnabled(r.enabled() == null || r.enabled());
    }
}
