package com.utkarsh.ratelimiter.policy.api;

import com.utkarsh.ratelimiter.policy.PolicyService;
import com.utkarsh.ratelimiter.stats.PolicyStatsResponse;
import com.utkarsh.ratelimiter.stats.PolicyStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin API for rate-limit policies. Requires ROLE_ADMIN (see SecurityConfiguration). */
@RestController
@RequestMapping("/api/v1/policies")
@Validated
@Tag(name = "Policies")
@SecurityRequirement(name = "bearer")
public class PolicyController {

    private final PolicyService service;
    private final PolicyStatsService stats;

    public PolicyController(PolicyService service, PolicyStatsService stats) {
        this.service = service;
        this.stats = stats;
    }

    @GetMapping("/{id}/stats")
    @Operation(summary = "Aggregated decisions for a policy across all instances (eventually consistent, ≤ flush interval)")
    public PolicyStatsResponse stats(@PathVariable long id) {
        return stats.stats(id);
    }

    @PostMapping
    @Operation(summary = "Create a policy")
    public ResponseEntity<PolicyResponse> create(@Valid @RequestBody PolicyRequest request) {
        PolicyResponse created = service.create(request);
        return ResponseEntity.created(URI.create("/api/v1/policies/" + created.id())).body(created);
    }

    @GetMapping
    @Operation(summary = "List policies (paged, ordered by id)")
    public PageResponse<PolicyResponse> list(@RequestParam(defaultValue = "0") @Min(0) int page,
                                             @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
        return PageResponse.of(service.list(PageRequest.of(page, size, Sort.by("id"))));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Get one policy")
    public PolicyResponse get(@PathVariable long id) {
        return service.get(id);
    }

    @PutMapping("/{id}")
    @Operation(summary = "Replace a policy; 'version' must match the stored version")
    public PolicyResponse update(@PathVariable long id, @Valid @RequestBody PolicyRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "Delete a policy")
    public ResponseEntity<Void> delete(@PathVariable long id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
