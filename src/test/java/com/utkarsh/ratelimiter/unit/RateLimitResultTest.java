package com.utkarsh.ratelimiter.unit;

import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNoException;

import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import org.junit.jupiter.api.Test;

class RateLimitResultTest {

    @Test
    void acceptsConsistentResults() {
        assertThatNoException().isThrownBy(() -> new RateLimitResult(true, 10, 9, 0, 1_000));
        assertThatNoException().isThrownBy(() -> new RateLimitResult(false, 10, 0, 500, 10_000));
    }

    @Test
    void rejectsInconsistentResults() {
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitResult(true, 10, 9, 5, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitResult(false, 10, 0, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitResult(true, 10, 11, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitResult(true, 10, -1, 0, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitResult(true, 10, 1, 0, -1));
        assertThatIllegalArgumentException().isThrownBy(() -> new RateLimitResult(true, 0, 0, 0, 0));
    }
}
