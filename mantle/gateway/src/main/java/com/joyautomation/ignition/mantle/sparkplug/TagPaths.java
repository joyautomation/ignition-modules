package com.joyautomation.ignition.mantle.sparkplug;

import java.util.ArrayList;
import java.util.List;

/**
 * Sparkplug names to tag paths. The layout is the wire's own hierarchy, the same one mantle shows:
 * {@code group/node/metric} and {@code group/node/device/metric}, with '/' in a metric name becoming folders.
 */
public final class TagPaths {
    /** Folder holding the module's own status tags for a node or device. */
    public static final String META = "_meta";

    private TagPaths() {
    }

    public static String node(String group, String edge) {
        return segment(group) + "/" + segment(edge);
    }

    public static String device(String group, String edge, String device) {
        return node(group, edge) + "/" + segment(device);
    }

    /** @param device null or empty for a node-level metric */
    public static String metric(String group, String edge, String device, String metricName) {
        String base = device == null || device.isEmpty() ? node(group, edge) : device(group, edge, device);
        return base + "/" + metricPath(metricName);
    }

    public static String metricPath(String metricName) {
        List<String> parts = new ArrayList<>();
        for (String part : metricName.split("/")) {
            if (!part.isBlank()) {
                parts.add(segment(part));
            }
        }
        return parts.isEmpty() ? "_" : String.join("/", parts);
    }

    /**
     * Ignition tag names allow letters, digits, underscore, space and {@code ' - : ( )}, and must start with a
     * letter, digit or underscore. Anything else becomes an underscore.
     */
    public static String segment(String raw) {
        String trimmed = raw.trim();
        StringBuilder sb = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean ok = Character.isLetterOrDigit(c) || c == '_'
                || (i > 0 && (c == ' ' || c == '\'' || c == '-' || c == ':' || c == '(' || c == ')'));
            sb.append(ok ? c : '_');
        }
        return sb.length() == 0 ? "_" : sb.toString();
    }
}
