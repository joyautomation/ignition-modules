package com.joyautomation.ignition.mantle.sparkplug;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import com.joyautomation.ignition.mantle.sparkplug.TagSink.MetricInfo;
import com.joyautomation.ignition.mantle.status.ModuleStatus;
import org.eclipse.tahu.message.SparkplugBPayloadDecoder;
import org.eclipse.tahu.message.model.Metric;
import org.eclipse.tahu.message.model.Metric.MetricBuilder;
import org.eclipse.tahu.message.model.MetricDataType;
import org.eclipse.tahu.message.model.PropertySet;
import org.eclipse.tahu.message.model.PropertyValue;
import org.eclipse.tahu.message.model.SparkplugBPayload;
import org.eclipse.tahu.message.model.SparkplugBPayload.SparkplugBPayloadBuilder;
import org.eclipse.tahu.message.model.Template;
import org.eclipse.tahu.message.model.Template.TemplateBuilder;
import org.eclipse.tahu.model.MetricDataTypeMap;
import org.eclipse.tahu.util.PayloadUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Sparkplug B host application state machine, with no MQTT and no Ignition in it: bytes and topics come in,
 * tag definitions and values go out through {@link TagSink}, commands go out through {@link Outbound}.
 * <p>
 * This is a port of nautilus's sparkplug/host/state.go and keeps its rules: a sequence gate with a bounded reorder
 * buffer, a gap timer that gives up and asks for a rebirth, one rebirth request per node per birth cycle, an NDEATH
 * honoured only when its bdSeq matches the birth's, and last values kept on death with quality carrying the news.
 */
public class HostState {
    private static final Logger logger = LoggerFactory.getLogger("Mantle.Host");

    static final String NAMESPACE = "spBv1.0";
    static final String BD_SEQ = "bdSeq";
    static final String REBIRTH_METRIC = "Node Control/Rebirth";
    static final int MAX_PENDING = 64;
    /** a rebirth request is QoS 0 and can be lost; after this long without a birth, asking again is allowed */
    static final long REBIRTH_RETRY_MS = 30_000;

    /** How commands leave. The transport owns topic construction and encoding. */
    public interface Outbound {
        /** @param device null for an NCMD */
        void publishCommand(String group, String edge, String device, SparkplugBPayload payload);
    }

    public record NodeKey(String group, String edge) {
    }

    /** Everything needed to turn a tag write back into a command. */
    record MetricRef(NodeKey node, String device, String metricName, MetricDataType type, List<TemplateStep> chain) {
    }

    /** One level of template nesting above a member metric. */
    record TemplateStep(String name, String templateRef, String version) {
    }

    private record Queued(String kind, String device, SparkplugBPayload payload) {
    }

    /** A birth is applied in two passes, DEFINE then VALUES, with the sink given time in between. DATA is one. */
    private enum Pass {
        DEFINE, VALUES, DATA
    }

    private static final class DeviceState {
        boolean online;
        Long birthMs;
        int metrics;
    }

    private static final class NodeState {
        final NodeKey key;
        final Map<String, DeviceState> devices = new HashMap<>();
        /** alias to (device, name); the spec scopes aliases to the edge node including its devices */
        final Map<Long, AliasTarget> aliases = new HashMap<>();
        /** birth-declared types per scope ("" is the node itself), so DATA that omits datatypes still decodes */
        final Map<String, MetricDataTypeMap> types = new HashMap<>();
        Map<Long, Queued> pending;
        ScheduledFuture<?> gapTimer;
        long gapGen;
        boolean online;
        boolean seqPrimed;
        boolean rebirthAsked;
        long rebirthAskedAt;
        long seq;
        long bdSeq = -1;
        Long birthMs;
        int metrics;
        /** templateRef -> usable as an Ignition UDT type. Absent means "flatten it". */
        final Map<String, Boolean> udtTypes = new HashMap<>();
        /** instance tag path -> really became a UDT instance (rather than being flattened) */
        final Map<String, Boolean> udtInstances = new HashMap<>();

        NodeState(NodeKey key) {
            this.key = key;
        }

        void stopGap() {
            if (gapTimer != null) {
                gapTimer.cancel(false);
                gapTimer = null;
            }
            // cancel() can't say whether the task already started; the generation makes a late run a no-op
            gapGen++;
        }
    }

    private record AliasTarget(String device, String name) {
    }

    private final Set<String> groups;
    private final long reorderTimeoutMs;
    private final TagSink sink;
    private final Outbound outbound;
    private final ScheduledExecutorService timer;
    private final SparkplugBPayloadDecoder decoder = new SparkplugBPayloadDecoder();

    private final Object lock = new Object();
    private final Map<NodeKey, NodeState> nodes = new HashMap<>();
    private final Map<String, MetricRef> refsByPath = new HashMap<>();
    private List<NodeKey> rebirthQueue = new ArrayList<>();

    public final AtomicLong messages = new AtomicLong();
    public final AtomicLong seqGaps = new AtomicLong();
    public final AtomicLong rebirthsRequested = new AtomicLong();
    public final AtomicLong decodeFailures = new AtomicLong();

    /**
     * @param groups group ids to consume; empty means every group on the broker
     */
    public HostState(Set<String> groups, long reorderTimeoutMs, TagSink sink, Outbound outbound,
                     ScheduledExecutorService timer) {
        this.groups = groups;
        this.reorderTimeoutMs = reorderTimeoutMs;
        this.sink = sink;
        this.outbound = outbound;
        this.timer = timer;
    }

    // ── inbound ──────────────────────────────────────────────────────────────

    public void handleMessage(String topic, byte[] bytes) {
        String[] parts = topic.split("/");
        if (parts.length < 4 || parts.length > 5 || !NAMESPACE.equals(parts[0]) || "STATE".equals(parts[1])) {
            return;
        }
        String group = parts[1];
        String kind = parts[2];
        String edge = parts[3];
        String device = parts.length == 5 ? parts[4] : "";
        if (group.isEmpty() || edge.isEmpty() || !validShape(kind, device)) {
            return;
        }
        if (!groups.isEmpty() && !groups.contains(group)) {
            return;
        }

        synchronized (lock) {
            messages.incrementAndGet();
            NodeState ns = nodes.computeIfAbsent(new NodeKey(group, edge), NodeState::new);
            boolean birth = kind.equals("NBIRTH") || kind.equals("DBIRTH");
            SparkplugBPayload payload = decode(ns, device, bytes, birth);
            if (payload != null) {
                dispatch(ns, kind, device, payload, birth);
            }
        }
        // outside the lock: publishing must never happen while holding it
        flushRebirths();
    }

    private void dispatch(NodeState ns, String kind, String device, SparkplugBPayload payload, boolean birth) {
        if (birth) {
            // Types register on receipt, not on apply: a DBIRTH can sit in the reorder buffer behind a missing
            // message while the DDATA that follows it arrives, and that DDATA needs the types to decode at all.
            if (kind.equals("NBIRTH")) {
                ns.types.clear();
            }
            registerTypes(ns, device, payload.getMetrics());
        }
        switch (kind) {
            case "NBIRTH" -> applyNBirth(ns, payload);
            case "NDEATH" -> applyNDeath(ns, payload);
            default -> {
                if (!ns.seqPrimed) {
                    // Data before a birth: we started mid-stream, or the gap timer gave up. Births are never
                    // retained, so the only cure is to ask.
                    askRebirth(ns);
                } else if (acceptSeq(ns, kind, device, payload)) {
                    applyOne(ns, kind, device, payload);
                    drainPending(ns);
                }
            }
        }
    }

    /** The broker connection dropped: nothing we hold is live any more. */
    public void connectionLost() {
        Date now = new Date();
        synchronized (lock) {
            for (NodeState ns : nodes.values()) {
                if (ns.online) {
                    takeNodeOffline(ns, now);
                }
                // anything asked for on the dead session never arrived
                ns.rebirthAsked = false;
                ns.seqPrimed = false;
            }
        }
    }

    private static boolean validShape(String kind, String device) {
        return switch (kind) {
            case "NBIRTH", "NDATA", "NDEATH" -> device.isEmpty();
            case "DBIRTH", "DDATA", "DDEATH" -> !device.isEmpty();
            // NCMD/DCMD are ours going out; anything else is not Sparkplug
            default -> false;
        };
    }

    private SparkplugBPayload decode(NodeState ns, String device, byte[] bytes, boolean birth) {
        try {
            MetricDataTypeMap types = birth ? null : ns.types.get(device);
            SparkplugBPayload payload = decoder.buildFromByteArray(bytes, types);
            return PayloadUtil.decompress(payload, types);
        } catch (Exception e) {
            decodeFailures.incrementAndGet();
            logger.debug("Undecodable payload from {}/{} ({} bytes)", ns.key.group(), ns.key.edge(), bytes.length, e);
            if (!birth) {
                // most often DATA without datatypes from a node whose birth we never saw
                askRebirth(ns);
            }
            return null;
        }
    }

    private void registerTypes(NodeState ns, String device, List<Metric> metrics) {
        MetricDataTypeMap map = ns.types.computeIfAbsent(device, d -> new MetricDataTypeMap());
        for (Metric m : metrics) {
            registerType(map, "", m);
        }
    }

    private void registerType(MetricDataTypeMap map, String prefix, Metric m) {
        if (m == null || m.getDataType() == null) {
            return;
        }
        if (m.hasName()) {
            map.addMetricDataType(prefix + m.getName(), m.getDataType());
        }
        if (m.hasAlias()) {
            map.addMetricDataType(m.getAlias(), m.getDataType());
        }
        if (TypeMapper.isTemplate(m.getDataType()) && m.getValue() instanceof Template t && m.hasName()) {
            for (Metric member : t.getMetrics()) {
                registerType(map, prefix + m.getName() + "/", member);
            }
        }
    }

    // ── births ───────────────────────────────────────────────────────────────

    private void applyNBirth(NodeState ns, SparkplugBPayload p) {
        long seq = p.getSeq() == null ? 0 : p.getSeq();
        if (seq != 0) {
            // not fatal: resynchronise from what we were sent rather than deadlock on a non-conformant edge
            logger.warn("NBIRTH seq is {} (expected 0) from {}/{}", seq, ns.key.group(), ns.key.edge());
        }
        ns.stopGap();
        ns.pending = null;
        ns.rebirthAsked = false;
        ns.online = true;
        ns.seq = seq;
        ns.seqPrimed = true;
        ns.aliases.clear();

        Date ts = payloadTime(p);
        // A node rebirth invalidates every device until its own DBIRTH. Their tags are left alone, since the
        // DBIRTHs are normally right behind and a stale/good flap on every tag would land in history.
        for (Map.Entry<String, DeviceState> dev : ns.devices.entrySet()) {
            dev.getValue().online = false;
            sink.update(metaPath(ns, dev.getKey(), "Online"), false, ts, false);
        }

        Long bd = bdSeqOf(p.getMetrics());
        if (bd != null) {
            ns.bdSeq = bd;
        }
        registerAliases(ns, "", p.getMetrics());
        int count = applyBirth(ns, "", p);
        ns.birthMs = ts.getTime();
        ns.metrics = count;
        sink.update(metaPath(ns, "", "Online"), true, ts, false);
        sink.update(metaPath(ns, "", "Last Birth"), ts, ts, false);
        logger.info("Node birth {}/{} bdSeq={} metrics={}", ns.key.group(), ns.key.edge(), ns.bdSeq, count);
    }

    private void applyDBirth(NodeState ns, String device, SparkplugBPayload p) {
        DeviceState dev = ns.devices.computeIfAbsent(device, d -> new DeviceState());
        dev.online = true;
        Date ts = payloadTime(p);
        registerAliases(ns, device, p.getMetrics());
        int count = applyBirth(ns, device, p);
        dev.birthMs = ts.getTime();
        dev.metrics = count;
        sink.update(metaPath(ns, device, "Online"), true, ts, false);
        sink.update(metaPath(ns, device, "Last Birth"), ts, ts, false);
        logger.info("Device birth {}/{}/{} metrics={}", ns.key.group(), ns.key.edge(), device, count);
    }

    /** Shape first, then values: see {@link TagSink#awaitDefinitions()}. */
    private int applyBirth(NodeState ns, String device, SparkplugBPayload p) {
        defineMeta(ns, device);
        harvestTypes(ns, p.getMetrics());
        applyMetrics(ns, device, p, Pass.DEFINE);
        sink.awaitDefinitions();
        return applyMetrics(ns, device, p, Pass.VALUES);
    }

    /**
     * Turns the template definitions a birth carries into UDT types, in the order the edge sent them — which
     * the spec's own convention puts nested types before the types that use them. A definition the sink can't
     * make a type from is simply absent from the map, and instances of it flatten into folders as before.
     */
    private void harvestTypes(NodeState ns, List<Metric> metrics) {
        for (Metric m : metrics) {
            if (m == null || !m.hasName() || !TypeMapper.isTemplate(m.getDataType())
                || !(m.getValue() instanceof Template t) || !t.isDefinition()) {
                continue;
            }
            String typeName = m.getName();
            List<TagSink.TypeMember> members = new ArrayList<>();
            boolean usable = true;
            for (Metric member : t.getMetrics()) {
                if (member == null || !member.hasName()) {
                    continue;
                }
                if (TypeMapper.isTemplate(member.getDataType())) {
                    // a member that is itself a UDT: the spec carries it as a templateRef to its own definition
                    String ref = member.getValue() instanceof Template nested ? nested.getTemplateRef() : null;
                    if (ref == null || !Boolean.TRUE.equals(ns.udtTypes.get(ref))) {
                        usable = false; // a nested type we never got a definition for
                        break;
                    }
                    members.add(new TagSink.TypeMember(member.getName(), null, ref, MetricInfo.DEFAULT));
                } else if (TypeMapper.toIgnition(member.getDataType()) == null) {
                    usable = false;
                    break;
                } else {
                    members.add(new TagSink.TypeMember(member.getName(), member.getDataType(), null,
                        infoOf(member.getName(), member)));
                }
            }
            ns.udtTypes.put(typeName, usable && !members.isEmpty() && sink.defineType(typeName, members));
        }
    }

    private void defineMeta(NodeState ns, String device) {
        MetricInfo status = new MetricInfo(null, null, null, null, true, false);
        sink.define(metaPath(ns, device, "Online"), MetricDataType.Boolean, status);
        sink.define(metaPath(ns, device, "Last Birth"), MetricDataType.DateTime,
            new MetricInfo(null, null, null, null, false, false));
    }

    private static String metaPath(NodeState ns, String device, String name) {
        return TagPaths.metric(ns.key.group(), ns.key.edge(), device, TagPaths.META + "/" + name);
    }

    private void registerAliases(NodeState ns, String device, List<Metric> metrics) {
        for (Metric m : metrics) {
            if (m != null && m.hasName() && m.hasAlias()) {
                ns.aliases.put(m.getAlias(), new AliasTarget(device, m.getName()));
            }
        }
    }

    private static Long bdSeqOf(List<Metric> metrics) {
        for (Metric m : metrics) {
            if (m != null && BD_SEQ.equals(m.getName()) && m.getValue() instanceof Number n) {
                return n instanceof BigInteger big ? big.longValue() : n.longValue();
            }
        }
        return null;
    }

    // ── deaths ───────────────────────────────────────────────────────────────

    private void applyNDeath(NodeState ns, SparkplugBPayload p) {
        Long bd = bdSeqOf(p.getMetrics());
        if (bd == null) {
            logger.warn("NDEATH without bdSeq from {}/{}, ignored", ns.key.group(), ns.key.edge());
            return;
        }
        if (!ns.seqPrimed && !ns.online) {
            // never saw this node's birth: a will from before we connected
            return;
        }
        if (bd != ns.bdSeq) {
            logger.info("Stale NDEATH from {}/{} ignored (bdSeq {} != current {})", ns.key.group(), ns.key.edge(),
                bd, ns.bdSeq);
            return;
        }
        // Stamped with our clock, not the payload's: an NDEATH is usually the will, composed when the node
        // connected, so its own time (if it has one) is older than every value the node has sent since.
        takeNodeOffline(ns, new Date());
        logger.info("Node death {}/{} bdSeq={}", ns.key.group(), ns.key.edge(), bd);
    }

    private void takeNodeOffline(NodeState ns, Date ts) {
        ns.stopGap();
        ns.pending = null;
        ns.online = false;
        ns.seqPrimed = false;
        ns.rebirthAsked = false;
        for (DeviceState dev : ns.devices.values()) {
            dev.online = false;
        }
        // Sparkplug's last value is the value: keep it, and let quality say the source is gone
        sink.markStale(TagPaths.node(ns.key.group(), ns.key.edge()), ts);
        sink.update(metaPath(ns, "", "Online"), false, ts, false);
        for (String device : ns.devices.keySet()) {
            sink.update(metaPath(ns, device, "Online"), false, ts, false);
        }
    }

    private void applyDDeath(NodeState ns, String device, SparkplugBPayload p) {
        DeviceState dev = ns.devices.computeIfAbsent(device, d -> new DeviceState());
        dev.online = false;
        Date ts = new Date();
        sink.markStale(TagPaths.device(ns.key.group(), ns.key.edge(), device), ts);
        sink.update(metaPath(ns, device, "Online"), false, ts, false);
    }

    // ── sequence tracking and the reorder buffer ─────────────────────────────

    /**
     * The gate for everything that consumes a seq (NDATA, DBIRTH, DDATA, DDEATH). True means apply now. An
     * out-of-order message is buffered and starts the reorder timer; a duplicate is dropped.
     */
    private boolean acceptSeq(NodeState ns, String kind, String device, SparkplugBPayload p) {
        if (p.getSeq() == null) {
            return true; // nothing to gate on
        }
        long seq = p.getSeq();
        long want = (ns.seq + 1) % 256;
        if (seq == want) {
            ns.seq = seq;
            return true;
        }
        if (seq == ns.seq) {
            return false; // QoS 1 redelivery of the message we just took
        }
        if (ns.pending == null) {
            ns.pending = new HashMap<>();
        }
        if (ns.pending.size() >= MAX_PENDING) {
            return false;
        }
        ns.pending.put(seq, new Queued(kind, device, p));
        startGap(ns);
        return false;
    }

    private void drainPending(NodeState ns) {
        while (ns.pending != null && !ns.pending.isEmpty()) {
            long next = (ns.seq + 1) % 256;
            Queued q = ns.pending.remove(next);
            if (q == null) {
                return; // still a hole; leave the timer running
            }
            ns.seq = next;
            applyOne(ns, q.kind(), q.device(), q.payload());
        }
        ns.stopGap();
    }

    private void applyOne(NodeState ns, String kind, String device, SparkplugBPayload p) {
        switch (kind) {
            case "DBIRTH" -> applyDBirth(ns, device, p);
            case "NDATA" -> applyMetrics(ns, "", p, Pass.DATA);
            case "DDATA" -> applyMetrics(ns, device, p, Pass.DATA);
            case "DDEATH" -> applyDDeath(ns, device, p);
            default -> {
            }
        }
    }

    private void startGap(NodeState ns) {
        if (ns.gapTimer != null) {
            return;
        }
        long gen = ns.gapGen;
        NodeKey key = ns.key;
        ns.gapTimer = timer.schedule(() -> gapExpired(key, gen), reorderTimeoutMs, TimeUnit.MILLISECONDS);
    }

    /** A sequence gap went unfilled: the buffered tail is unusable, so drop it and ask the node to birth again. */
    void gapExpired(NodeKey key, long gen) {
        int held;
        synchronized (lock) {
            NodeState ns = nodes.get(key);
            if (ns == null || ns.gapGen != gen) {
                return; // cancelled, or superseded by a newer gap
            }
            held = ns.pending == null ? 0 : ns.pending.size();
            ns.gapTimer = null;
            ns.gapGen++;
            ns.pending = null;
            ns.seqPrimed = false;
            seqGaps.incrementAndGet();
            askRebirth(ns);
        }
        logger.warn("Sequence gap from {}/{}, requesting rebirth ({} buffered messages dropped)", key.group(),
            key.edge(), held);
        flushRebirths();
    }

    // ── rebirth requests ─────────────────────────────────────────────────────

    /**
     * One request per node per birth cycle, so a dark site cannot storm the broker. The request is re-armed after
     * REBIRTH_RETRY_MS, because a lost NCMD would otherwise leave a talking node unborn forever.
     */
    private void askRebirth(NodeState ns) {
        long now = System.currentTimeMillis();
        if (ns.rebirthAsked && now - ns.rebirthAskedAt < REBIRTH_RETRY_MS) {
            return;
        }
        ns.rebirthAsked = true;
        ns.rebirthAskedAt = now;
        rebirthQueue.add(ns.key);
    }

    /** Ask nodes we knew about in a previous life to birth again, so quiet ones don't stay stale until they talk. */
    public void requestRebirths(Iterable<NodeKey> keys) {
        synchronized (lock) {
            for (NodeKey key : keys) {
                if (groups.isEmpty() || groups.contains(key.group())) {
                    askRebirth(nodes.computeIfAbsent(key, NodeState::new));
                }
            }
        }
        flushRebirths();
    }

    private void flushRebirths() {
        List<NodeKey> queue;
        synchronized (lock) {
            if (rebirthQueue.isEmpty()) {
                return;
            }
            queue = rebirthQueue;
            rebirthQueue = new ArrayList<>();
        }
        for (NodeKey key : queue) {
            try {
                Metric rebirth = new MetricBuilder(REBIRTH_METRIC, MetricDataType.Boolean, true).createMetric();
                rebirthsRequested.incrementAndGet();
                outbound.publishCommand(key.group(), key.edge(), null, commandPayload(rebirth));
            } catch (Exception e) {
                logger.warn("Could not request rebirth from {}/{}", key.group(), key.edge(), e);
            }
        }
    }

    // ── value application ────────────────────────────────────────────────────

    private int applyMetrics(NodeState ns, String device, SparkplugBPayload p, Pass pass) {
        Date payloadTs = payloadTime(p);
        int count = 0;
        for (Metric m : p.getMetrics()) {
            if (m == null) {
                continue;
            }
            String name = m.hasName() && !m.getName().isEmpty() ? m.getName() : null;
            String scope = device;
            if (name == null && m.hasAlias()) {
                AliasTarget target = ns.aliases.get(m.getAlias());
                if (target == null) {
                    if (pass == Pass.DATA) {
                        askRebirth(ns); // an alias we were never told about
                    }
                    continue;
                }
                name = target.name();
                scope = target.device();
            }
            if (name == null || BD_SEQ.equals(name)) {
                continue;
            }
            Date ts = m.getTimestamp() != null ? m.getTimestamp() : payloadTs;
            count += applyMetric(ns, scope, name, name, m, ts, pass, List.of(), false);
        }
        return count;
    }

    /**
     * @param pathName the metric's full slash-separated name, which becomes its tag path
     * @param ownName the metric's name at its own level: the same as pathName at the top, the member name inside a
     *                template. This is what goes back on the wire in a command.
     * @param chain the template instances above this metric, outermost first
     */
    private int applyMetric(NodeState ns, String device, String pathName, String ownName, Metric m, Date ts,
                            Pass pass, List<TemplateStep> chain, boolean inUdt) {
        if (TypeMapper.isTemplate(m.getDataType())) {
            if (!(m.getValue() instanceof Template template) || template.isDefinition()) {
                return 0; // definitions describe a shape (harvestTypes has them); only instances carry values
            }
            String instancePath = TagPaths.metric(ns.key.group(), ns.key.edge(), device, pathName);
            if (chain.isEmpty() && Boolean.TRUE.equals(ns.udtTypes.get(template.getTemplateRef()))) {
                // A top-level instance of a type we declared: one UDT instance, and Ignition builds the members.
                // Nested instances need no call of their own — they come with their parent's type.
                if (pass == Pass.DEFINE) {
                    ns.udtInstances.put(instancePath, sink.defineInstance(instancePath, template.getTemplateRef()));
                }
                inUdt = Boolean.TRUE.equals(ns.udtInstances.get(instancePath));
            }
            List<TemplateStep> inner = new ArrayList<>(chain);
            inner.add(new TemplateStep(ownName, template.getTemplateRef(), template.getVersion()));
            int count = 0;
            for (Metric member : template.getMetrics()) {
                if (member != null && member.hasName()) {
                    count += applyMetric(ns, device, pathName + "/" + member.getName(), member.getName(), member, ts,
                        pass, inner, inUdt);
                }
            }
            return count;
        }

        String path = TagPaths.metric(ns.key.group(), ns.key.edge(), device, pathName);
        if (pass == Pass.DEFINE) {
            if (TypeMapper.toIgnition(m.getDataType()) == null) {
                return 0;
            }
            // A member of a UDT instance already exists: Ignition built it from the type. Declaring it again
            // would fight the type, so it only gets what the type cannot give it — a way to write back.
            MetricInfo info = infoOf(pathName, m);
            if (inUdt) {
                if (info.writable()) {
                    sink.allowWrites(path);
                }
            } else {
                sink.define(path, m.getDataType(), info);
            }
            refsByPath.put(path, new MetricRef(ns.key, device, ownName, m.getDataType(), chain));
            return 1;
        }
        if (!refsByPath.containsKey(path)) {
            if (pass == Pass.DATA) {
                // a metric that was never in a birth; the spec forbids it, and we have no type to create it with
                askRebirth(ns);
            }
            return 0;
        }

        boolean historical = Boolean.TRUE.equals(m.isHistorical());
        if (Boolean.TRUE.equals(m.isNull()) || m.getValue() == null) {
            sink.updateNull(path, ts);
        } else {
            sink.update(path, TypeMapper.toIgnitionValue(m.getDataType(), m.getValue()), ts, historical);
        }
        return 1;
    }

    private static MetricInfo infoOf(String name, Metric m) {
        boolean control = name.startsWith("Node Control/") || name.startsWith("Device Control/");
        boolean historize = !control && !Boolean.TRUE.equals(m.isTransient());
        PropertySet props = m.getProperties();
        if (props == null || props.isEmpty()) {
            return new MetricInfo(null, null, null, null, historize, true);
        }
        return new MetricInfo(string(props, "engUnit"), string(props, "documentation"), number(props, "engLow"),
            number(props, "engHigh"), historize, !Boolean.TRUE.equals(bool(props, "readOnly")));
    }

    private static String string(PropertySet props, String key) {
        PropertyValue<?> v = props.get(key);
        return v == null || v.getValue() == null ? null : v.getValue().toString();
    }

    private static Double number(PropertySet props, String key) {
        PropertyValue<?> v = props.get(key);
        return v != null && v.getValue() instanceof Number n ? n.doubleValue() : null;
    }

    private static Boolean bool(PropertySet props, String key) {
        PropertyValue<?> v = props.get(key);
        return v != null && v.getValue() instanceof Boolean b ? b : null;
    }

    /** An NDEATH need not carry a timestamp, and Tahu reports a missing one as epoch zero rather than null. */
    private static Date payloadTime(SparkplugBPayload p) {
        return p.getTimestamp() != null && p.getTimestamp().getTime() > 0 ? p.getTimestamp() : new Date();
    }

    // ── writes ───────────────────────────────────────────────────────────────

    /**
     * A tag was written: send it to the edge as an NCMD or DCMD, typed the way the birth declared it. The tag's
     * value is not touched here; it changes when the edge reports the new value back.
     *
     * @return false when the path isn't a Sparkplug metric or its node is offline
     */
    public boolean write(String path, Object value) {
        MetricRef ref;
        synchronized (lock) {
            ref = refsByPath.get(path);
            NodeState ns = ref == null ? null : nodes.get(ref.node());
            if (ns == null || !ns.online) {
                return false;
            }
        }
        try {
            Metric metric = new MetricBuilder(ref.metricName(), ref.type(),
                TypeMapper.toSparkplugValue(ref.type(), value)).createMetric();
            // A template is never written whole: wrap the one member in partial instances and let the edge merge.
            for (int i = ref.chain().size() - 1; i >= 0; i--) {
                TemplateStep step = ref.chain().get(i);
                Template partial = new TemplateBuilder().version(step.version()).templateRef(step.templateRef())
                    .definition(false).addMetric(metric).createTemplate();
                metric = new MetricBuilder(step.name(), MetricDataType.Template, partial).createMetric();
            }
            String device = ref.device().isEmpty() ? null : ref.device();
            outbound.publishCommand(ref.node().group(), ref.node().edge(), device, commandPayload(metric));
            return true;
        } catch (Exception e) {
            logger.warn("Could not write {}", path, e);
            return false;
        }
    }

    private static SparkplugBPayload commandPayload(Metric metric) {
        SparkplugBPayload payload = new SparkplugBPayloadBuilder().addMetric(metric).createPayload();
        payload.setTimestamp(new Date());
        return payload;
    }

    // ── introspection ────────────────────────────────────────────────────────

    /** A consistent picture of every node, taken under the lock and handed back as plain data. */
    public List<ModuleStatus.Node> nodeStatus() {
        synchronized (lock) {
            return nodes.values().stream()
                .sorted(Comparator.comparing((NodeState n) -> n.key.group()).thenComparing(n -> n.key.edge()))
                .map(ns -> new ModuleStatus.Node(ns.key.group(), ns.key.edge(), ns.online, ns.bdSeq, ns.birthMs,
                    ns.metrics, ns.rebirthAsked, ns.devices.entrySet().stream()
                        .sorted(Map.Entry.comparingByKey())
                        .map(e -> new ModuleStatus.Device(e.getKey(), e.getValue().online, e.getValue().birthMs,
                            e.getValue().metrics))
                        .toList()))
                .toList();
        }
    }

    public ModuleStatus.Counters counters() {
        synchronized (lock) {
            return new ModuleStatus.Counters(messages.get(), seqGaps.get(), rebirthsRequested.get(),
                decodeFailures.get(), (int) nodes.values().stream().filter(n -> n.online).count(), nodes.size());
        }
    }

    /** Ask one node to birth again, from the status page's button. False when we have never heard of it. */
    public boolean requestRebirth(String group, String edge) {
        NodeKey key = new NodeKey(group, edge);
        synchronized (lock) {
            NodeState ns = nodes.get(key);
            if (ns == null) {
                return false;
            }
            // an operator pressing the button means now, whatever the debounce thinks
            ns.rebirthAsked = false;
            askRebirth(ns);
        }
        flushRebirths();
        return true;
    }

    public boolean isOnline(String group, String edge) {
        synchronized (lock) {
            NodeState ns = nodes.get(new NodeKey(group, edge));
            return ns != null && ns.online;
        }
    }

    public int nodeCount() {
        synchronized (lock) {
            return nodes.size();
        }
    }

    public int onlineNodeCount() {
        synchronized (lock) {
            return (int) nodes.values().stream().filter(n -> n.online).count();
        }
    }
}
