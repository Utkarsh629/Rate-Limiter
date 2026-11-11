package com.utkarsh.ratelimiter.filter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.utkarsh.ratelimiter.algorithm.RateLimitResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;

/**
 * Turns a {@link RateLimitResult} into HTTP. The JSON bodies are RFC 7807 problem documents
 * ({@code type}, {@code title}, {@code status}, {@code detail}, {@code instance}) extended with the
 * fields of the project's rate-limit contract ({@code error}, {@code message}, {@code retryAfterSeconds}),
 * so generic problem+json clients and the documented contract both work.
 */
public class RateLimitResponseWriter {

    private final ObjectMapper json;

    public RateLimitResponseWriter(ObjectMapper json) {
        this.json = json;
    }

    /** Headers for any response that was rate limited (allowed or rejected). */
    public void writeHeaders(HttpServletResponse response, RateLimitResult result) {
        response.setHeader(RateLimitHeaders.LIMIT, Long.toString(result.limit()));
        response.setHeader(RateLimitHeaders.REMAINING, Long.toString(result.remaining()));
        response.setHeader(RateLimitHeaders.RESET, Long.toString(RateLimitHeaders.resetSeconds(result.resetAfterMillis())));
    }

    /** 429: quota exhausted. */
    public void writeRejected(HttpServletRequest request, HttpServletResponse response, RateLimitResult result) throws IOException {
        long retryAfter = RateLimitHeaders.retryAfterSeconds(result.retryAfterMillis());
        writeHeaders(response, result);
        writeProblem(request, response, HttpStatus.TOO_MANY_REQUESTS, "Rate limit exceeded", retryAfter);
    }

    /** 503: a FAIL_CLOSED policy could not be evaluated because the rate-limit store is unavailable. */
    public void writeUnavailable(HttpServletRequest request, HttpServletResponse response, long retryAfterSeconds) throws IOException {
        writeProblem(request, response, HttpStatus.SERVICE_UNAVAILABLE, "Rate limiting is temporarily unavailable", retryAfterSeconds);
    }

    private void writeProblem(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                              String message, long retryAfterSeconds) throws IOException {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", status.getReasonPhrase());
        body.put("status", status.value());
        body.put("error", status.getReasonPhrase());
        body.put("message", message);
        body.put("detail", message);
        body.put("retryAfterSeconds", retryAfterSeconds);
        body.put("instance", request.getRequestURI());
        body.put("timestamp", Instant.now().toString());

        response.setStatus(status.value());
        response.setHeader(RateLimitHeaders.RETRY_AFTER, Long.toString(retryAfterSeconds));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        json.writeValue(response.getOutputStream(), body);
    }
}
