package com.utkarsh.ratelimiter.redis;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** Loads a Lua script from the classpath once, with this library's own class loader. */
public final class ScriptText {

    private ScriptText() {
    }

    public static String load(String location) {
        try (InputStream in = ScriptText.class.getClassLoader().getResourceAsStream(location)) {
            if (in == null) {
                throw new IllegalStateException("Script not found on classpath: " + location);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
