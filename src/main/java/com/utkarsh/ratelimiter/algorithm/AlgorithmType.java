package com.utkarsh.ratelimiter.algorithm;

/**
 * The supported rate-limiting algorithms. The {@link #keySuffix()} is part of every Redis key, so
 * switching a policy to a different algorithm starts from fresh state instead of reading a value
 * written in another algorithm's format.
 */
public enum AlgorithmType {

    TOKEN_BUCKET("tb"),
    FIXED_WINDOW("fw"),
    SLIDING_WINDOW("sw");

    private final String keySuffix;

    AlgorithmType(String keySuffix) {
        this.keySuffix = keySuffix;
    }

    public String keySuffix() {
        return keySuffix;
    }
}
