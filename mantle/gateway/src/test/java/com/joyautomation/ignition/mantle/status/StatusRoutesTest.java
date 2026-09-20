package com.joyautomation.ignition.mantle.status;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import com.inductiveautomation.ignition.common.gson.JsonObject;
import org.junit.jupiter.api.Test;

/**
 * The status page reads this JSON, so its shape is a contract. These tests are what stops a rename in a record
 * quietly emptying a column on the page.
 */
class StatusRoutesTest {
    private static final ModuleStatus.Links LINKS =
        new ModuleStatus.Links("/app/services/historian/providers");

    /** The page offers a way to fix what it is complaining about, so the link is part of the contract. */
    @Test
    void theGatewaysOwnConfigPagesAreLinked() {
        JsonObject links = StatusRoutes.status(List.of(connection()), LINKS).getAsJsonObject("links");
        assertEquals("/app/services/historian/providers", links.get("historian").getAsString());
    }

    /** No Historian module installed: a null, so the page can say what to do rather than offer a dead link. */
    @Test
    void anAbsentConfigPageIsNullRatherThanABrokenLink() {
        JsonObject links = StatusRoutes.status(List.of(connection()), new ModuleStatus.Links(null))
            .getAsJsonObject("links");
        assertTrue(links.get("historian").isJsonNull());
    }

    @Test
    void aConnectionSerializesEverythingThePageDraws() {
        JsonObject json = StatusRoutes.status(List.of(connection()), LINKS);

        assertTrue(json.get("asOfMs").getAsLong() > 0);
        JsonObject c = json.getAsJsonArray("connections").get(0).getAsJsonObject();
        assertEquals("dev-broker", c.get("name").getAsString());
        assertEquals("tcp://broker:1883", c.get("brokerUrl").getAsString());
        assertEquals("joy-dev", c.get("hostId").getAsString());
        assertEquals("Sparkplug", c.get("tagProvider").getAsString());
        assertEquals("Core", c.get("historian").getAsString());
        assertTrue(c.get("connected").getAsBoolean());
        assertTrue(c.get("lastError").isJsonNull());
        assertEquals(List.of("Plant"), c.getAsJsonArray("groups").asList().stream()
            .map(e -> e.getAsString()).toList());

        JsonObject counters = c.getAsJsonObject("counters");
        assertEquals(1234, counters.get("messages").getAsLong());
        assertEquals(2, counters.get("seqGaps").getAsLong());
        assertEquals(3, counters.get("rebirthsRequested").getAsLong());
        assertEquals(1, counters.get("decodeFailures").getAsLong());
        assertEquals(1, counters.get("nodesOnline").getAsInt());
        assertEquals(2, counters.get("nodesKnown").getAsInt());
    }

    @Test
    void nodesAndTheirDevicesSerialize() {
        JsonObject c = StatusRoutes.status(List.of(connection()), LINKS)
            .getAsJsonArray("connections").get(0).getAsJsonObject();

        JsonObject node = c.getAsJsonArray("nodes").get(0).getAsJsonObject();
        assertEquals("Plant", node.get("group").getAsString());
        assertEquals("Edge1", node.get("edge").getAsString());
        assertTrue(node.get("online").getAsBoolean());
        assertEquals(7, node.get("bdSeq").getAsLong());
        assertEquals(1000L, node.get("lastBirthMs").getAsLong());
        assertEquals(11, node.get("metrics").getAsInt());
        assertFalse(node.get("awaitingRebirth").getAsBoolean());

        JsonObject device = node.getAsJsonArray("devices").get(0).getAsJsonObject();
        assertEquals("PLC1", device.get("id").getAsString());
        assertTrue(device.get("online").getAsBoolean());
        assertEquals(6, device.get("metrics").getAsInt());
    }

    /** A node that has never birthed has no birth time; the page has to be handed a null, not a zero. */
    @Test
    void aNodeThatHasNeverBirthedReportsNullNotZero() {
        JsonObject c = StatusRoutes.status(List.of(connection()), LINKS)
            .getAsJsonArray("connections").get(0).getAsJsonObject();

        JsonObject dark = c.getAsJsonArray("nodes").get(1).getAsJsonObject();
        assertEquals("Edge2", dark.get("edge").getAsString());
        assertFalse(dark.get("online").getAsBoolean());
        assertTrue(dark.get("lastBirthMs").isJsonNull(), "never-birthed node must not claim a birth time");
        assertTrue(dark.get("awaitingRebirth").getAsBoolean());
        assertTrue(dark.getAsJsonArray("devices").isEmpty());
    }

    @Test
    void aDisconnectedConnectionCarriesItsError() {
        ModuleStatus.Connection down = new ModuleStatus.Connection("down", "tcp://nope:1883", "h", "Sparkplug",
            "Core", true, false, "ConnectException: Connection refused", List.of(),
            new ModuleStatus.Counters(0, 0, 0, 0, 0, 0), List.of());

        JsonObject c = StatusRoutes.status(List.of(down), LINKS).getAsJsonArray("connections").get(0).getAsJsonObject();
        assertFalse(c.get("connected").getAsBoolean());
        assertEquals("ConnectException: Connection refused", c.get("lastError").getAsString());
    }

    /** No historian means nothing is being recorded, and the page has to be able to say so. */
    @Test
    void aConnectionWithNoHistorianReportsNull() {
        ModuleStatus.Connection none = new ModuleStatus.Connection("c", "tcp://b:1883", "h", "Sparkplug", null,
            true, true, null, List.of(), new ModuleStatus.Counters(0, 0, 0, 0, 0, 0), List.of());

        JsonObject c = StatusRoutes.status(List.of(none), LINKS).getAsJsonArray("connections").get(0).getAsJsonObject();
        assertTrue(c.get("historian").isJsonNull(), "a missing historian must be visible, not absent");
    }

    private static ModuleStatus.Connection connection() {
        ModuleStatus.Node online = new ModuleStatus.Node("Plant", "Edge1", true, 7, 1000L, 11, false,
            List.of(new ModuleStatus.Device("PLC1", true, 1001L, 6)));
        ModuleStatus.Node dark = new ModuleStatus.Node("Plant", "Edge2", false, -1, null, 0, true, List.of());
        return new ModuleStatus.Connection("dev-broker", "tcp://broker:1883", "joy-dev", "Sparkplug", "Core",
            true, true, null, List.of("Plant"), new ModuleStatus.Counters(1234, 2, 3, 1, 1, 2),
            List.of(online, dark));
    }
}
