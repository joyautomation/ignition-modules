package com.joyautomation.ignition.mantle.status;

import java.util.List;

/**
 * What the module knows about itself, as plain data: the shape the status route serves and the status page
 * renders. Records rather than a live view, so a reader never holds a lock and never sees a half-updated node.
 */
public final class ModuleStatus {
    private ModuleStatus() {
    }

    /**
     * One broker connection and everything born under it.
     *
     * @param historian the tag historian new tags are pointed at, or null when the gateway has none — in which
     *                  case nothing is being recorded, which an operator has to be able to see
     */
    public record Connection(String name, String brokerUrl, String hostId, String tagProvider, String historian,
                             boolean enabled, boolean connected, String lastError, List<String> groups,
                             Counters counters, List<Node> nodes) {
    }

    /**
     * Health in five numbers. Gaps and decode failures are the ones worth watching: both mean data was lost
     * between the edge and here, and both should sit at zero on a healthy link.
     */
    public record Counters(long messages, long seqGaps, long rebirthsRequested, long decodeFailures,
                           int nodesOnline, int nodesKnown) {
    }

    /** An edge node. {@code online} is Sparkplug's sense of it: born, and not since dead. */
    public record Node(String group, String edge, boolean online, long bdSeq, Long lastBirthMs, int metrics,
                       boolean awaitingRebirth, List<Device> devices) {
    }

    public record Device(String id, boolean online, Long lastBirthMs, int metrics) {
    }
}
