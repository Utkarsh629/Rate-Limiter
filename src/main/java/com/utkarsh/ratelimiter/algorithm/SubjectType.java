package com.utkarsh.ratelimiter.algorithm;

/** What a quota is counted against. */
public enum SubjectType {

    /** An authenticated user id. */
    USER("u"),

    /** A client IP address (anonymous callers, or IP-keyed policies). */
    IP("ip");

    private final String keyCode;

    SubjectType(String keyCode) {
        this.keyCode = keyCode;
    }

    public String keyCode() {
        return keyCode;
    }
}
