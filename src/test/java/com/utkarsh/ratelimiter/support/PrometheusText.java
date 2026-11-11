package com.utkarsh.ratelimiter.support;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Minimal parser for the Prometheus text exposition format, for assertions in tests. */
public final class PrometheusText {

    public record Sample(String name, Map<String, String> labels, double value) {
    }

    private static final Pattern LINE = Pattern.compile("^([a-zA-Z_:][a-zA-Z0-9_:]*)(\\{(.*)})?\\s+(\\S+)");
    private static final Pattern LABEL = Pattern.compile("([a-zA-Z_][a-zA-Z0-9_]*)=\"((?:[^\"\\\\]|\\\\.)*)\"");

    private final List<Sample> samples = new ArrayList<>();

    public PrometheusText(String body) {
        for (String line : body.split("\n")) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            Matcher m = LINE.matcher(line);
            if (!m.find()) {
                continue;
            }
            Map<String, String> labels = new LinkedHashMap<>();
            if (m.group(3) != null) {
                Matcher l = LABEL.matcher(m.group(3));
                while (l.find()) {
                    labels.put(l.group(1), l.group(2));
                }
            }
            samples.add(new Sample(m.group(1), labels, Double.parseDouble(m.group(4))));
        }
    }

    public List<Sample> samples() {
        return samples;
    }

    /** Sum of all samples with this name whose labels include all of {@code match} (k1, v1, k2, v2, ...). */
    public double sum(String name, String... match) {
        double total = 0;
        for (Sample s : samples) {
            if (!s.name().equals(name)) {
                continue;
            }
            boolean ok = true;
            for (int i = 0; i < match.length; i += 2) {
                ok &= match[i + 1].equals(s.labels().get(match[i]));
            }
            if (ok) {
                total += s.value();
            }
        }
        return total;
    }
}
