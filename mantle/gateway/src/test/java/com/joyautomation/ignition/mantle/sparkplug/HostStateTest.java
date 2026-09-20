package com.joyautomation.ignition.mantle.sparkplug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import org.eclipse.tahu.message.SparkplugBPayloadEncoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class HostStateTest {
    private static final String NODE = "spBv1.0/Plant/%s/Edge1";
    private static final String DEVICE = "spBv1.0/Plant/%s/Edge1/PLC1";

    private final MemorySink sink = new MemorySink();
    private final List<Command> commands = new ArrayList<>();
    private ScheduledExecutorService timer;
    private HostState host;

    record Command(String group, String edge, String device, SparkplugBPayload payload) {
    }

    @BeforeEach
    void setUp() {
        timer = Executors.newSingleThreadScheduledExecutor();
        host = new HostState(Set.of(), 50, sink,
            (group, edge, device, payload) -> commands.add(new Command(group, edge, device, payload)), timer);
    }

    @AfterEach
    void tearDown() {
        timer.shutdownNow();
    }

    @Test
    void birthsCreateTagsInTheWireHierarchy() throws Exception {
        nbirth(1);
        send(DEVICE, "DBIRTH", 1, false, metric("Tank/Level", 7L, MetricDataType.Float, 42.5f));

        assertEquals(MetricDataType.Boolean, sink.types.get("Plant/Edge1/Node Control/Rebirth"));
        assertEquals(MetricDataType.Float, sink.types.get("Plant/Edge1/PLC1/Tank/Level"));
        assertEquals(42.5f, sink.values.get("Plant/Edge1/PLC1/Tank/Level"));
        assertEquals(true, sink.values.get("Plant/Edge1/_meta/Online"));
        assertEquals(true, sink.values.get("Plant/Edge1/PLC1/_meta/Online"));
        assertFalse(sink.types.containsKey("Plant/Edge1/bdSeq"), "bdSeq is protocol, not a tag");
    }

    @Test
    void aBirthIsShapeFirstThenValues() throws Exception {
        nbirthWith(1, metric("A", null, MetricDataType.Int32, 1), metric("B", null, MetricDataType.Int32, 2));

        int await = sink.events.indexOf("await");
        int lastDefine = sink.events.lastIndexOf("define Plant/Edge1/B");
        int firstUpdate = sink.events.indexOf("update Plant/Edge1/A");
        assertTrue(lastDefine >= 0 && await > lastDefine, "every tag is defined before the sink is awaited");
        assertTrue(firstUpdate > await, "no value is sent until the sink says the tags are ready: " + sink.events);
    }

    @Test
    void historyIsOnUnlessTheEdgeSaysTransientOrItIsAControl() throws Exception {
        Metric scratch = new MetricBuilder("Scratch", MetricDataType.Int32, 1).isTransient(true).createMetric();
        send(NODE, "NBIRTH", 0, false, bdSeq(1), rebirthControl(), scratch,
            metric("Flow", null, MetricDataType.Double, 1.0));

        assertTrue(sink.infos.get("Plant/Edge1/Flow").historize());
        assertFalse(sink.infos.get("Plant/Edge1/Scratch").historize());
        assertFalse(sink.infos.get("Plant/Edge1/Node Control/Rebirth").historize());
    }

    @Test
    void aliasOnlyDataWithoutDatatypesStillDecodes() throws Exception {
        nbirth(1);
        send(DEVICE, "DBIRTH", 1, false, metric("Tank/Level", 7L, MetricDataType.Float, 1.0f));

        Metric byAlias = new MetricBuilder(7L, MetricDataType.Float, 9.75f).createMetric();
        send(DEVICE, "DDATA", 2, true, byAlias);

        assertEquals(9.75f, sink.values.get("Plant/Edge1/PLC1/Tank/Level"));
        assertTrue(commands.isEmpty(), "no rebirth should have been needed");
    }

    @Test
    void dataBeforeBirthAsksForExactlyOneRebirth() throws Exception {
        for (int seq = 5; seq < 9; seq++) {
            send(NODE, "NDATA", seq, false, metric("Flow", null, MetricDataType.Double, 1.0));
        }

        assertEquals(1, commands.size());
        Command cmd = commands.get(0);
        assertEquals("Edge1", cmd.edge());
        assertNull(cmd.device());
        assertEquals("Node Control/Rebirth", cmd.payload().getMetrics().get(0).getName());
        assertEquals(true, cmd.payload().getMetrics().get(0).getValue());
        assertTrue(sink.types.isEmpty(), "nothing may be created from data alone");
    }

    @Test
    void outOfOrderMessagesApplyInSequence() throws Exception {
        nbirthWith(1, metric("Count", null, MetricDataType.Int32, 0));

        send(NODE, "NDATA", 2, false, metric("Count", null, MetricDataType.Int32, 2));
        assertEquals(0, sink.values.get("Plant/Edge1/Count"), "seq 2 must wait for seq 1");
        send(NODE, "NDATA", 1, false, metric("Count", null, MetricDataType.Int32, 1));

        assertEquals(List.of(0, 1, 2), sink.history.get("Plant/Edge1/Count"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void duplicateDeliveryIsDropped() throws Exception {
        nbirthWith(1, metric("Count", null, MetricDataType.Int32, 0));
        send(NODE, "NDATA", 1, false, metric("Count", null, MetricDataType.Int32, 1));
        send(NODE, "NDATA", 1, false, metric("Count", null, MetricDataType.Int32, 1));

        assertEquals(List.of(0, 1), sink.history.get("Plant/Edge1/Count"));
    }

    @Test
    void anUnfilledGapDropsTheTailAndAsksForRebirth() throws Exception {
        nbirthWith(1, metric("Count", null, MetricDataType.Int32, 0));
        send(NODE, "NDATA", 3, false, metric("Count", null, MetricDataType.Int32, 3));

        Thread.sleep(250);

        assertEquals(1, host.seqGaps.get());
        assertEquals(1, commands.size());
        assertEquals(0, sink.values.get("Plant/Edge1/Count"), "the buffered tail is unusable");
    }

    @Test
    void sequenceWrapsAt256() throws Exception {
        nbirthWith(1, metric("Count", null, MetricDataType.Int32, 0));
        for (int i = 1; i <= 300; i++) {
            send(NODE, "NDATA", i % 256, false, metric("Count", null, MetricDataType.Int32, i));
        }

        assertEquals(300, sink.values.get("Plant/Edge1/Count"));
        assertTrue(commands.isEmpty());
    }

    @Test
    void deathWithTheWrongBdSeqIsIgnored() throws Exception {
        nbirthWith(4, metric("Flow", null, MetricDataType.Double, 1.0));

        send(NODE, "NDEATH", null, false, bdSeq(3));
        assertTrue(host.isOnline("Plant", "Edge1"));
        assertTrue(sink.stale.isEmpty());

        send(NODE, "NDEATH", null, false, bdSeq(4));
        assertFalse(host.isOnline("Plant", "Edge1"));
        assertTrue(sink.stale.contains("Plant/Edge1"));
        assertEquals(false, sink.values.get("Plant/Edge1/_meta/Online"));
        assertEquals(1.0, sink.values.get("Plant/Edge1/Flow"), "the last value is kept");
    }

    @Test
    void aDeathIsStampedByTheHostBecauseAWillIsAsOldAsItsConnection() throws Exception {
        nbirthWith(4, metric("Flow", null, MetricDataType.Double, 1.0));
        long before = System.currentTimeMillis();

        // what a broker publishes on the node's behalf: composed at connect time, here long ago
        sendAt(new Date(1000), NODE, "NDEATH", null, bdSeq(4));

        assertTrue(sink.staleAt.get("Plant/Edge1").getTime() >= before,
            "a death stamped in the past is older than the tag's live value, and Ignition files it as backfill");
        assertTrue(sink.times.get("Plant/Edge1/_meta/Online").getTime() >= before);
    }

    @Test
    void deviceDeathOnlyTakesThatDeviceDown() throws Exception {
        nbirth(1);
        send(DEVICE, "DBIRTH", 1, false, metric("Level", null, MetricDataType.Float, 1.0f));
        send(DEVICE, "DDEATH", 2, false);

        assertEquals(Set.of("Plant/Edge1/PLC1"), sink.stale);
        assertTrue(host.isOnline("Plant", "Edge1"));
        assertEquals(false, sink.values.get("Plant/Edge1/PLC1/_meta/Online"));
    }

    @Test
    void brokerLossMakesEverythingStale() throws Exception {
        nbirth(1);
        host.connectionLost();

        assertTrue(sink.stale.contains("Plant/Edge1"));
        assertFalse(host.isOnline("Plant", "Edge1"));
    }

    @Test
    void templateInstancesFlattenIntoFolders() throws Exception {
        nbirthWith(1, motorTemplate("Motor1", 1200, true));

        assertEquals(1200, sink.values.get("Plant/Edge1/Motor1/Speed"));
        assertEquals(true, sink.values.get("Plant/Edge1/Motor1/Running"));
    }

    @Test
    void templateDefinitionsCreateNoTags() throws Exception {
        Template def = new TemplateBuilder().definition(true)
            .addMetric(metric("Speed", null, MetricDataType.Int32, 0)).createTemplate();
        nbirthWith(1, new MetricBuilder("_types_/Motor", MetricDataType.Template, def).createMetric());

        assertFalse(sink.types.keySet().stream().anyMatch(p -> p.contains("_types_")));
    }

    @Test
    void writesUseTheBirthDeclaredTypeNotTheValueType() throws Exception {
        nbirth(1);
        send(DEVICE, "DBIRTH", 1, false, metric("Setpoint", null, MetricDataType.Int16, (short) 5));

        assertTrue(host.write("Plant/Edge1/PLC1/Setpoint", 40L));

        Command cmd = commands.get(0);
        assertEquals("PLC1", cmd.device());
        Metric written = cmd.payload().getMetrics().get(0);
        assertEquals("Setpoint", written.getName());
        assertEquals(MetricDataType.Int16, written.getDataType());
        assertEquals((short) 40, written.getValue());
    }

    @Test
    void aTemplateMemberWriteIsAPartialInstance() throws Exception {
        nbirthWith(1, motorTemplate("Motor1", 1200, true));

        assertTrue(host.write("Plant/Edge1/Motor1/Speed", 900));

        Metric instance = commands.get(0).payload().getMetrics().get(0);
        assertEquals("Motor1", instance.getName());
        assertEquals(MetricDataType.Template, instance.getDataType());
        Template partial = (Template) instance.getValue();
        assertEquals("Motor", partial.getTemplateRef());
        assertEquals(1, partial.getMetrics().size(), "only the written member goes out");
        assertEquals("Speed", partial.getMetrics().get(0).getName());
        assertEquals(900, partial.getMetrics().get(0).getValue());
    }

    @Test
    void writesToAnOfflineNodeAreRefused() throws Exception {
        nbirthWith(2, metric("Setpoint", null, MetricDataType.Int32, 5));
        send(NODE, "NDEATH", null, false, bdSeq(2));

        assertFalse(host.write("Plant/Edge1/Setpoint", 6));
        assertTrue(commands.isEmpty());
    }

    @Test
    void groupFilterIgnoresOtherGroups() throws Exception {
        host = new HostState(Set.of("Other"), 50, sink, (g, e, d, p) -> commands.add(new Command(g, e, d, p)), timer);
        nbirth(1);

        assertTrue(sink.types.isEmpty());
        assertEquals(0, host.nodeCount());
    }

    @Test
    void unsignedValuesWidenInsteadOfWrapping() throws Exception {
        nbirthWith(1, metric("Total", null, MetricDataType.UInt32, 4_000_000_000L));

        assertEquals(4_000_000_000L, sink.values.get("Plant/Edge1/Total"));
    }

    @Test
    void knownNodesCanBeAskedToRebirthOnStartup() {
        host.requestRebirths(List.of(new HostState.NodeKey("Plant", "Edge1"), new HostState.NodeKey("Plant", "Edge2")));

        assertEquals(2, commands.size());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void nbirth(long bdSeq) throws Exception {
        nbirthWith(bdSeq);
    }

    private void nbirthWith(long bdSeq, Metric... extra) throws Exception {
        Metric[] all = new Metric[extra.length + 2];
        all[0] = bdSeq(bdSeq);
        all[1] = rebirthControl();
        System.arraycopy(extra, 0, all, 2, extra.length);
        send(NODE, "NBIRTH", 0, false, all);
    }

    private static Metric bdSeq(long value) throws Exception {
        return new MetricBuilder("bdSeq", MetricDataType.Int64, value).createMetric();
    }

    private static Metric rebirthControl() throws Exception {
        return new MetricBuilder("Node Control/Rebirth", MetricDataType.Boolean, false).createMetric();
    }

    private static Metric metric(String name, Long alias, MetricDataType type, Object value) throws Exception {
        MetricBuilder builder = new MetricBuilder(name, type, value);
        if (alias != null) {
            builder.alias(alias);
        }
        return builder.createMetric();
    }

    private static Metric motorTemplate(String name, int speed, boolean running) throws Exception {
        Template instance = new TemplateBuilder().templateRef("Motor").definition(false)
            .addMetric(metric("Speed", null, MetricDataType.Int32, speed))
            .addMetric(metric("Running", null, MetricDataType.Boolean, running)).createTemplate();
        return new MetricBuilder(name, MetricDataType.Template, instance).createMetric();
    }

    private void send(String topicPattern, String kind, Integer seq, boolean stripDataTypes, Metric... metrics)
        throws Exception {
        sendAt(new Date(), topicPattern, kind, seq, stripDataTypes, metrics);
    }

    private void sendAt(Date timestamp, String topicPattern, String kind, Integer seq, Metric... metrics)
        throws Exception {
        sendAt(timestamp, topicPattern, kind, seq, false, metrics);
    }

    private void sendAt(Date timestamp, String topicPattern, String kind, Integer seq, boolean stripDataTypes,
                        Metric... metrics) throws Exception {
        SparkplugBPayloadBuilder builder = seq == null
            ? new SparkplugBPayloadBuilder() : new SparkplugBPayloadBuilder((long) seq);
        builder.setTimestamp(timestamp);
        for (Metric m : metrics) {
            builder.addMetric(m);
        }
        byte[] bytes = new SparkplugBPayloadEncoder().getBytes(builder.createPayload(), stripDataTypes);
        host.handleMessage(String.format(topicPattern, kind), bytes);
    }

    /** Remembers everything it was told, in order. */
    static class MemorySink implements TagSink {
        final Map<String, MetricDataType> types = new HashMap<>();
        final Map<String, MetricInfo> infos = new HashMap<>();
        final Map<String, Object> values = new HashMap<>();
        final Map<String, List<Object>> history = new HashMap<>();
        final Set<String> stale = new HashSet<>();
        final Map<String, Date> staleAt = new HashMap<>();
        final Map<String, Date> times = new HashMap<>();
        final List<String> events = new ArrayList<>();

        @Override
        public void define(String path, MetricDataType type, MetricInfo info) {
            types.put(path, type);
            infos.put(path, info);
            events.add("define " + path);
        }

        @Override
        public void update(String path, Object value, Date timestamp, boolean historical) {
            values.put(path, value);
            times.put(path, timestamp);
            events.add("update " + path);
            history.computeIfAbsent(path, p -> new ArrayList<>()).add(value);
        }

        @Override
        public void awaitDefinitions() {
            events.add("await");
        }

        @Override
        public void updateNull(String path, Date timestamp) {
            values.put(path, null);
        }

        @Override
        public void markStale(String folderPath, Date timestamp) {
            stale.add(folderPath);
            staleAt.put(folderPath, timestamp);
        }
    }
}
