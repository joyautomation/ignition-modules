package com.joyautomation.ignition.mantle.tags;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;

import com.inductiveautomation.ignition.common.config.BasicBoundPropertySet;
import com.inductiveautomation.ignition.common.config.BoundPropertySet;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.config.BasicTagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.CollisionPolicy;
import com.inductiveautomation.ignition.common.tags.config.TagConfiguration;
import com.inductiveautomation.ignition.common.tags.config.TagConfigurationModel;
import com.inductiveautomation.ignition.common.tags.config.properties.TagHistoryProps;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.inductiveautomation.ignition.common.tags.model.TagPath;
import com.inductiveautomation.ignition.common.tags.model.TagProvider;
import com.inductiveautomation.ignition.common.tags.paths.parser.TagPathParser;
import com.inductiveautomation.ignition.common.util.TimeUnits;
import com.inductiveautomation.ignition.gateway.model.GatewayContext;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProvider;
import com.inductiveautomation.ignition.gateway.tags.managed.ManagedTagProviderConfiguration;
import com.joyautomation.ignition.mantle.sparkplug.HostState.NodeKey;
import com.joyautomation.ignition.mantle.sparkplug.TagPaths;
import com.joyautomation.ignition.mantle.sparkplug.TagSink;
import com.joyautomation.ignition.mantle.sparkplug.TagSink.TypeMember;
import com.joyautomation.ignition.mantle.sparkplug.TypeMapper;
import org.eclipse.tahu.message.model.MetricDataType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Tags in a managed tag provider. The provider persists its tags and allows customization, so what gets created
 * here are ordinary tags: alarms, scaling, scripts, security and history settings go on them directly and survive
 * rebirths and restarts. There is no second set of tags.
 * <p>
 * What makes that safe is that Ignition keeps two layers per tag. {@code configureTag} writes the programmatic layer
 * (stored under "prg" in the tag's JSON); edits made in the Designer are stored beside it and win. So a rebirth can
 * refresh what the edge says about a metric (datatype, units, range, documentation, the history default) without
 * touching a user's override. Verified against 8.3.9: a tag with history switched off, different units and an alarm
 * kept all three across a rebirth that set history on and the original units.
 */
public class ManagedTagSink implements TagSink {
    private static final Logger logger = LoggerFactory.getLogger("Mantle.Tags");
    private static final Object NULL = new Object();
    private static final long CLOCK_SKEW_WARN_MS = 5000;
    private static final long DEFINITION_TIMEOUT_MS = 30_000;
    /** Ignition keeps a provider's UDT types here; it is not a tag folder and never holds Sparkplug data. */
    private static final String TYPES_FOLDER = "_types_";

    private final GatewayContext context;
    private final String providerName;
    private final ManagedTagProvider provider;
    private final boolean historize;
    private final String historyProvider;

    /** what the last birth said about a tag */
    private record Definition(DataType dataType, MetricInfo info) {
    }

    /** UDT types declared this session, by name, with the members they were built from */
    private final Map<String, List<TypeMember>> definedTypes = new ConcurrentHashMap<>();
    /** UDT instance path -> type name, or FLATTENED when it could not become one */
    private final Map<String, String> instances = new ConcurrentHashMap<>();
    private final Set<String> flattenWarned = ConcurrentHashMap.newKeySet();
    /** paths that already have a write handler registered */
    private final Set<String> writable = ConcurrentHashMap.newKeySet();
    private static final String FLATTENED = "\u0000flattened";

    /** paths of the tags that were already in the provider when we started */
    private final Set<String> existing = ConcurrentHashMap.newKeySet();
    /** tags defined during this session */
    private final Map<String, Definition> defined = new ConcurrentHashMap<>();
    /** last good value per tag, so quality can change without losing the value */
    private final Map<String, Object> lastValues = new ConcurrentHashMap<>();
    /** tags configured since the last awaitDefinitions() that the gateway has to (re)initialize */
    private final List<Pending> pending = new CopyOnWriteArrayList<>();

    private record Pending(String path, DataType dataType) {
    }

    /** newest timestamp written per tag: the floor a live value must clear */
    private final Map<String, Long> lastTimes = new ConcurrentHashMap<>();
    private final AtomicBoolean skewWarned = new AtomicBoolean();

    /** one per broker connection feeding this provider; each only answers for paths it created */
    private final List<BiPredicate<String, Object>> writers = new CopyOnWriteArrayList<>();

    public ManagedTagSink(GatewayContext context, String providerName, boolean historize, String historyProvider) {
        this.context = context;
        this.providerName = providerName;
        this.historize = historize;
        this.historyProvider = historyProvider == null || historyProvider.isBlank() ? null : historyProvider.trim();

        ManagedTagProviderConfiguration configuration = ManagedTagProviderConfiguration.builder(providerName)
            .persistTags(true)
            .allowTagCustomization(true)
            .allowTagDeletion(true)
            // values older than a tag's current one go to history without moving the live value backwards,
            // which is what a store-and-forward flush needs
            .allowBackfill(true)
            .hasDataTypes(true)
            .build();
        this.provider = context.getTagManager().getOrCreateManagedProvider(configuration);
        loadExisting();
    }

    /** A writer is called when a tag is written, and returns whether it sent the command. */
    public void addWriter(BiPredicate<String, Object> writer) {
        writers.add(writer);
    }

    public void removeWriter(BiPredicate<String, Object> writer) {
        writers.remove(writer);
    }

    private boolean write(String path, Object value) {
        for (BiPredicate<String, Object> writer : writers) {
            if (writer.test(path, value)) {
                return true;
            }
        }
        return false;
    }

    /** Release the provider without deleting its tags. */
    public void shutdown() {
        provider.shutdown(false);
    }

    /**
     * Edge nodes we had tags for before this session, read back from the folder layout. A group or node ID that
     * had to be altered to make a legal tag name won't round-trip; that node just waits for its next message.
     */
    public Set<NodeKey> knownNodes() {
        Set<NodeKey> keys = new LinkedHashSet<>();
        for (String path : existing) {
            String[] parts = path.split("/");
            if (parts.length >= 3) {
                keys.add(new NodeKey(parts[0], parts[1]));
            }
        }
        return keys;
    }

    private void loadExisting() {
        try {
            TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
            if (tagProvider == null) {
                return;
            }
            List<TagConfigurationModel> roots = tagProvider
                .getTagConfigsAsync(List.of(TagPathParser.parse(providerName, "")), true, true)
                .get(60, TimeUnit.SECONDS);
            for (TagConfigurationModel root : roots) {
                collect("", root);
            }
            logger.info("Tag provider '{}' already holds {} Sparkplug tags", providerName, existing.size());
        } catch (Exception e) {
            // Without this list a rebirth would reconfigure tags that already exist, so say so loudly.
            logger.warn("Could not read existing tags from provider '{}'; existing tag customizations are at risk "
                + "until this is resolved", providerName, e);
        }
    }

    private void collect(String parentPath, TagConfigurationModel model) {
        for (TagConfigurationModel child : model.getChildren()) {
            String path = parentPath.isEmpty() ? child.getName() : parentPath + "/" + child.getName();
            if (parentPath.isEmpty() && TYPES_FOLDER.equals(child.getName())) {
                continue; // UDT definitions, not data from an edge node
            }
            if (child.getType() == TagObjectType.AtomicTag) {
                existing.add(path);
            } else {
                collect(path, child);
            }
        }
    }

    /**
     * A Sparkplug template definition becomes an Ignition UDT type under {@code _types_}. The managed provider
     * only hosts types at all because of {@code hasDataTypes(true)} above, and its own configureTag() cannot
     * build one — that has to go through the tag configuration API, the same route the Designer uses.
     */
    @Override
    public boolean defineType(String typeName, List<TypeMember> members) {
        String path = TYPES_FOLDER + "/" + TagPaths.segment(typeName);
        List<TypeMember> previous = definedTypes.get(typeName);
        if (members.equals(previous)) {
            return true; // a rebirth saying the same thing
        }
        try {
            TagConfiguration type = BasicTagConfiguration.createNew(TagPathParser.parse(providerName, path));
            type.setType(TagObjectType.UdtType);
            for (TypeMember member : members) {
                TagConfiguration child =
                    BasicTagConfiguration.createNew(TagPathParser.parse(providerName, path + "/" + member.name()));
                if (member.isNested()) {
                    child.setType(TagObjectType.UdtInstance);
                    child.set(WellKnownTagProps.TypeId, member.nestedTypeRef());
                } else {
                    child.setType(TagObjectType.AtomicTag);
                    child.set(WellKnownTagProps.DataType, TypeMapper.toIgnition(member.type()));
                    applyMetadata(child, member.info());
                }
                type.addChild(child);
            }
            QualityCode result = save(type);
            if (result != null && result.isNotGood()) {
                logger.warn("Could not create UDT type '{}' in '{}': {}. Instances of it will be folders of tags "
                    + "instead.", typeName, providerName, result);
                definedTypes.remove(typeName);
                return false;
            }
            definedTypes.put(typeName, List.copyOf(members));
            logger.info("UDT type '{}' in '{}' has {} members", typeName, providerName, members.size());
            return true;
        } catch (Exception e) {
            logger.warn("Could not create UDT type '{}' in '{}'; instances of it will be folders of tags instead",
                typeName, providerName, e);
            definedTypes.remove(typeName);
            return false;
        }
    }

    @Override
    public boolean defineInstance(String path, String typeName) {
        String known = instances.get(path);
        if (known != null) {
            return known.equals(typeName);
        }
        try {
            // A path that is already a folder of tags (an older version of this module made it that way, or a
            // person did) cannot be turned into a UDT instance without destroying what is there. Say so once and
            // leave it flattened; deleting a user's tags to change their shape is not ours to do.
            TagObjectType current = typeOf(path);
            if (current != null && current != TagObjectType.UdtInstance) {
                if (flattenWarned.add(path)) {
                    logger.info("{} already exists as {} in '{}', so it stays a folder of tags. Delete it if you "
                        + "want it rebuilt as a UDT instance of '{}'.", path, current, providerName, typeName);
                }
                instances.put(path, FLATTENED);
                return false;
            }
            TagConfiguration instance = BasicTagConfiguration.createNew(TagPathParser.parse(providerName, path));
            instance.setType(TagObjectType.UdtInstance);
            instance.set(WellKnownTagProps.TypeId, typeName);
            QualityCode result = save(instance);
            if (result != null && result.isNotGood()) {
                logger.warn("Could not create UDT instance {} of '{}': {}; it stays a folder of tags",
                    path, typeName, result);
                instances.put(path, FLATTENED);
                return false;
            }
            instances.put(path, typeName);
            return true;
        } catch (Exception e) {
            logger.warn("Could not create UDT instance {} of '{}'; it stays a folder of tags", path, typeName, e);
            instances.put(path, FLATTENED);
            return false;
        }
    }

    /** The tag object type at a path today, or null when nothing is there. */
    private TagObjectType typeOf(String path) {
        try {
            TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
            if (tagProvider == null) {
                return null;
            }
            List<TagConfigurationModel> configs = tagProvider
                .getTagConfigsAsync(List.of(TagPathParser.parse(providerName, path)), false, false)
                .get(15, TimeUnit.SECONDS);
            if (configs.isEmpty()) {
                return null;
            }
            TagObjectType type = configs.get(0).getType();
            return type == TagObjectType.Unknown ? null : type;
        } catch (Exception e) {
            logger.debug("Could not read the tag type at {}", path, e);
            return null;
        }
    }

    /** Saves one tag configuration through the provider, merging rather than replacing what a user has set. */
    private QualityCode save(TagConfiguration config) throws Exception {
        TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
        if (tagProvider == null) {
            return QualityCode.Bad_NotFound;
        }
        List<QualityCode> results = tagProvider
            .saveTagConfigsAsync(List.of(config), CollisionPolicy.MergeOverwrite)
            .get(30, TimeUnit.SECONDS);
        return results.isEmpty() ? null : results.get(0);
    }

    @Override
    public void define(String path, MetricDataType type, MetricInfo info) {
        DataType dataType = TypeMapper.toIgnition(type);
        if (dataType == null) {
            return;
        }
        Definition definition = new Definition(dataType, info);
        Definition previous = defined.put(path, definition);
        if (definition.equals(previous)) {
            return; // a rebirth that says nothing new
        }
        if (previous == null || previous.dataType() != dataType) {
            // created, or retyped: either way the gateway is about to (re)initialize it
            pending.add(new Pending(path, dataType));
        }
        if (info.writable()) {
            allowWrites(path);
        }

        BoundPropertySet props = new BasicBoundPropertySet();
        props.set(WellKnownTagProps.DataType, dataType);
        applyMetadata(props, info);
        provider.configureTag(path, props);
    }

    /** Units, range, documentation and the history default — everything a birth says about a metric. */
    private void applyMetadata(BoundPropertySet props, MetricInfo info) {
        if (info.engUnit() != null) {
            props.set(WellKnownTagProps.EngUnit, info.engUnit());
        }
        if (info.documentation() != null) {
            props.set(WellKnownTagProps.Documentation, info.documentation());
        }
        if (info.engLow() != null) {
            props.set(WellKnownTagProps.EngLow, info.engLow());
        }
        if (info.engHigh() != null) {
            props.set(WellKnownTagProps.EngHigh, info.engHigh());
        }
        if (historize && info.historize()) {
            String historian = resolveHistorian();
            if (historian != null) {
                props.set(TagHistoryProps.HistoryEnabled, true);
                props.set(TagHistoryProps.HistoryProvider, historian);
                // Ignition's default is at most one sample a second. The edge has already decided what is worth
                // reporting (its RBE deadbands), and a store-and-forward flush arrives far faster than that, so
                // store what we are told. Like everything else here, a user can override it per tag.
                props.set(TagHistoryProps.HistoryTimeDeadband, 0);
                props.set(TagHistoryProps.HistoryTimeDeadbandUnits, TimeUnits.MS);
            }
        }
    }

    /**
     * configureTag returns before the gateway has built the tag. A value pushed in that window is applied and then
     * wiped when the tag initializes to null, and a metric that only reports on change (a setpoint, Online) would
     * then sit at null until the next rebirth. So wait until the newest tag in the batch is really there: tags are
     * built in the order they were configured, and births are rare enough to afford it.
     */
    @Override
    public void awaitDefinitions() {
        if (pending.isEmpty()) {
            return;
        }
        List<Pending> batch = List.copyOf(pending);
        pending.removeAll(batch);
        Pending last = batch.get(batch.size() - 1);
        long deadline = System.currentTimeMillis() + DEFINITION_TIMEOUT_MS;
        try {
            TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
            List<TagPath> probe = List.of(TagPathParser.parse(providerName, last.path()));
            while (!ready(tagProvider, probe, last.dataType())) {
                if (System.currentTimeMillis() > deadline) {
                    logger.warn("Gave up waiting for {} new tags to appear in '{}' (last: {}); their first values "
                        + "may be lost until the next change", batch.size(), providerName, last.path());
                    break;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Could not confirm new tags in '{}'", providerName, e);
        }
        // the gateway stamped each new tag's initial null with its own clock, later than the birth was stamped
        long now = System.currentTimeMillis();
        for (Pending p : batch) {
            lastTimes.merge(p.path(), now, Math::max);
        }
    }

    private static boolean ready(TagProvider tagProvider, List<TagPath> probe, DataType dataType) throws Exception {
        List<TagConfigurationModel> configs = tagProvider.getTagConfigsAsync(probe, false, false)
            .get(10, TimeUnit.SECONDS);
        return configs.size() == 1 && configs.get(0).getType() == TagObjectType.AtomicTag
            && configs.get(0).get(WellKnownTagProps.DataType) == dataType;
    }

    private String resolveHistorian() {
        if (historyProvider != null) {
            return historyProvider;
        }
        List<String> historians = context.getTagHistoryManager().getTagHistoryProviders();
        return historians.isEmpty() ? null : historians.get(0);
    }

    @Override
    public void allowWrites(String path) {
        if (writable.add(path)) {
            provider.registerWriteHandler(path, (tagPath, value) ->
                write(path, value) ? QualityCode.Good : QualityCode.Bad_Failure);
        }
    }

    @Override
    public void update(String path, Object value, Date timestamp, boolean historical) {
        if (!path.contains("/" + TagPaths.META + "/")) {
            lastValues.put(path, value == null ? NULL : value);
        }
        provider.updateValue(path, value, QualityCode.Good, historical ? timestamp : liveTime(path, timestamp));
    }

    /**
     * The provider allows backfill, which means Ignition sends ANY value older than a tag's current one to history
     * and leaves the live value alone. That is right for a store-and-forward flush and wrong for everything else:
     * a birth stamped a few milliseconds before the gateway finished creating the tag, or any value from an edge
     * whose clock runs behind ours, would silently never show up. So a live value always lands after the tag's
     * last one; only a metric the edge flagged is_historical keeps a timestamp from the past.
     */
    private Date liveTime(String path, Date timestamp) {
        long time = timestamp.getTime();
        long floor = lastTimes.getOrDefault(path, 0L);
        if (time <= floor) {
            if (floor - time > CLOCK_SKEW_WARN_MS && skewWarned.compareAndSet(false, true)) {
                logger.warn("{} reported a value stamped {} s behind this gateway's view of the tag. The edge's clock "
                    + "is probably behind; its live values are being re-stamped so they still show up.", path,
                    (floor - time) / 1000);
            }
            time = floor + 1;
        }
        lastTimes.put(path, time);
        return new Date(time);
    }

    @Override
    public void updateNull(String path, Date timestamp) {
        update(path, null, timestamp, false);
    }

    @Override
    public void markStale(String folderPath, Date timestamp) {
        String prefix = folderPath + "/";
        for (Map.Entry<String, Object> entry : lastValues.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                Object value = entry.getValue() == NULL ? null : entry.getValue();
                provider.updateValue(entry.getKey(), value, QualityCode.Bad_Stale,
                    liveTime(entry.getKey(), timestamp));
            }
        }
    }
}
