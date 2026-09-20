package com.joyautomation.ignition.mantle.status;

import java.util.List;
import java.util.function.Supplier;

import com.inductiveautomation.ignition.common.gson.JsonArray;
import com.inductiveautomation.ignition.common.gson.JsonObject;
import com.joyautomation.ignition.mantle.mqtt.BrokerConnection;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The JSON behind the status page. Written by hand rather than reflected out of the records: the wire shape of
 * a page's API is worth deciding on purpose, and it stops a rename in a record quietly breaking the page.
 */
public final class StatusRoutes {
    private StatusRoutes() {
    }

    public static JsonObject status(List<ModuleStatus.Connection> connections) {
        JsonArray array = new JsonArray();
        for (ModuleStatus.Connection c : connections) {
            array.add(connection(c));
        }
        JsonObject root = new JsonObject();
        root.add("connections", array);
        root.addProperty("asOfMs", System.currentTimeMillis());
        return root;
    }

    /**
     * Asks one node to birth again. 404 rather than a silent success when the node is unknown: an operator who
     * pressed the button deserves to know it went nowhere.
     */
    public static JsonObject rebirth(Supplier<List<BrokerConnection>> connections, String group, String edge,
                                     HttpServletResponse response) {
        boolean asked = false;
        for (BrokerConnection connection : connections.get()) {
            asked |= connection.host().requestRebirth(group, edge);
        }
        JsonObject result = new JsonObject();
        result.addProperty("requested", asked);
        if (!asked) {
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            result.addProperty("error", "no node " + group + "/" + edge + " on any connection");
        }
        return result;
    }

    private static JsonObject connection(ModuleStatus.Connection c) {
        JsonObject json = new JsonObject();
        json.addProperty("name", c.name());
        json.addProperty("brokerUrl", c.brokerUrl());
        json.addProperty("hostId", c.hostId());
        json.addProperty("tagProvider", c.tagProvider());
        json.addProperty("historian", c.historian());
        json.addProperty("connected", c.connected());
        json.addProperty("lastError", c.lastError());
        JsonArray groups = new JsonArray();
        c.groups().forEach(groups::add);
        json.add("groups", groups);

        ModuleStatus.Counters n = c.counters();
        JsonObject counters = new JsonObject();
        counters.addProperty("messages", n.messages());
        counters.addProperty("seqGaps", n.seqGaps());
        counters.addProperty("rebirthsRequested", n.rebirthsRequested());
        counters.addProperty("decodeFailures", n.decodeFailures());
        counters.addProperty("nodesOnline", n.nodesOnline());
        counters.addProperty("nodesKnown", n.nodesKnown());
        json.add("counters", counters);

        JsonArray nodes = new JsonArray();
        for (ModuleStatus.Node node : c.nodes()) {
            nodes.add(node(node));
        }
        json.add("nodes", nodes);
        return json;
    }

    private static JsonObject node(ModuleStatus.Node n) {
        JsonObject json = new JsonObject();
        json.addProperty("group", n.group());
        json.addProperty("edge", n.edge());
        json.addProperty("online", n.online());
        json.addProperty("bdSeq", n.bdSeq());
        json.addProperty("lastBirthMs", n.lastBirthMs());
        json.addProperty("metrics", n.metrics());
        json.addProperty("awaitingRebirth", n.awaitingRebirth());
        JsonArray devices = new JsonArray();
        for (ModuleStatus.Device d : n.devices()) {
            JsonObject device = new JsonObject();
            device.addProperty("id", d.id());
            device.addProperty("online", d.online());
            device.addProperty("lastBirthMs", d.lastBirthMs());
            device.addProperty("metrics", d.metrics());
            devices.add(device);
        }
        json.add("devices", devices);
        return json;
    }
}
