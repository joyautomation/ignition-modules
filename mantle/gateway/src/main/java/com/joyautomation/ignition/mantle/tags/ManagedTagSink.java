package com.joyautomation.ignition.mantle.tags;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

import com.inductiveautomation.ignition.common.config.BasicBoundPropertySet;
import com.inductiveautomation.ignition.common.config.BoundPropertySet;
import com.inductiveautomation.ignition.common.config.Property;
import com.inductiveautomation.ignition.common.config.PropertySet;
import com.inductiveautomation.ignition.common.model.values.QualityCode;
import com.inductiveautomation.ignition.common.sqltags.history.InterpolationMode;
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
import com.inductiveautomation.ignition.gateway.tags.managed.DeletionHandler.DeletionResponse;
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
 * (stored under "prg" in the tag's JSON); edits made in the Designer are stored beside it and win. So the history
 * default this module applies never disturbs a history setting a person made, and an alarm, a script or a
 * security setting is never the module's to touch.
 * <p>
 * The edge owns what the birth carries, though. A metric's datatype, units, range and documentation come from the
 * device, and when a person has overridden one of them in the Designer and the next birth says otherwise, the
 * birth wins ({@link #refreshProperties}). What a birth does not carry is left exactly as the person set it.
 * Verified against 8.3.9: a tag with history switched off, hand-typed units and an alarm kept history off and the
 * alarm across a rebirth, and got its units back from the edge.
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

    /**
     * What the last birth said about a tag, plus the historian it was given. The historian is part of the
     * definition on purpose: a tag defined while no historian existed must be redefined when one appears,
     * rather than staying silently unrecorded until something restarts.
     */
    private record Definition(DataType dataType, MetricInfo info, String historian) {
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

    /** UDT instances created since the last awaitDefinitions(), with the atomic members Ignition has to build */
    private final List<PendingInstance> pendingInstances = new CopyOnWriteArrayList<>();

    private record PendingInstance(String path, List<String> members) {
    }

    /**
     * What each open birth found in its folder before it defined anything, by folder: a tag missing from it is one
     * this birth creates. Keyed by folder because one provider can take births from several connections at once.
     */
    private final Map<String, Map<String, TagObjectType>> births = new ConcurrentHashMap<>();
    /** new atomic tags, by birth folder, whose module layer leaves history off until the birth's values are in */
    private final Map<String, Set<String>> heldTags = new ConcurrentHashMap<>();
    /** members of new UDT instances, by birth folder, whose instance holds history off until the same moment */
    private final Map<String, Set<String>> heldMembers = new ConcurrentHashMap<>();

    /** every tag this birth described, so what the birth carries can be put back over a person's override */
    private final List<Refresh> refresh = new CopyOnWriteArrayList<>();

    private record Refresh(String path, DataType dataType, MetricInfo info) {
    }

    /** tag configurations read or saved per request; a birth of thousands of metrics is split up, not refused */
    private static final int CONFIG_BATCH = 500;

    /** newest timestamp written per tag: the floor a live value must clear */
    private final Map<String, Long> lastTimes = new ConcurrentHashMap<>();
    private final AtomicBoolean skewWarned = new AtomicBoolean();
    private final AtomicBoolean noHistorianWarned = new AtomicBoolean();

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
        this.provider.setDeletionHandler(this::deleted);
        loadExisting();
    }

    /**
     * Someone deleted a tag or folder here. It stays deleted; we only forget it, so the next birth builds it again
     * in full (and a death doesn't resurrect it from the last values to mark it stale). Covers a delete while the
     * node is still publishing, which {@link #beginBirth} cannot see: by then a value has recreated the path bare.
     */
    private DeletionResponse deleted(TagPath tagPath) {
        String path = tagPath.toStringPartial();
        if (path.equals(TYPES_FOLDER) || path.startsWith(TYPES_FOLDER + "/")) {
            definedTypes.keySet().removeIf(name -> within(TYPES_FOLDER + "/" + TagPaths.segment(name), path));
        } else {
            defined.keySet().removeIf(p -> within(p, path));
            instances.keySet().removeIf(p -> within(p, path));
            lastValues.keySet().removeIf(p -> within(p, path));
        }
        return DeletionResponse.Allowed;
    }

    private static boolean within(String path, String root) {
        return root.isEmpty() || path.equals(root) || path.startsWith(root + "/");
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
     * Forget what this session defined under a folder that is no longer there. Without this, deleting a node's
     * folder in the Designer gets it back bare: the next birth matches what we remember, so nothing is defined,
     * and the first value through updateValue() creates each tag with no history, no historian and no units — the
     * failure this module exists to prevent, and a silent one. A UDT instance that is now something else, and a
     * type deleted from _types_, are forgotten the same way. A false "missing" costs only a redundant
     * configureTag, which never touches a user's layer; a false "present" is the bug, so an unreadable folder is
     * the only case where the cache is trusted.
     */
    @Override
    public void beginBirth(String folderPath) {
        Map<String, TagObjectType> present = presentUnder(folderPath);
        if (present == null) {
            births.remove(folderPath); // unknown, so nothing is treated as new: history goes on at once, as before
            return;
        }
        births.put(folderPath, present);
        defined.keySet().removeIf(path -> gone(path, folderPath, present, TagObjectType.AtomicTag));
        // a flattened instance is a folder by design, so only its presence matters
        instances.entrySet().removeIf(e -> gone(e.getKey(), folderPath, present,
            FLATTENED.equals(e.getValue()) ? null : TagObjectType.UdtInstance));
        if (!definedTypes.isEmpty()) {
            Map<String, TagObjectType> types = presentUnder(TYPES_FOLDER);
            if (types != null) {
                definedTypes.keySet().removeIf(name ->
                    gone(TYPES_FOLDER + "/" + TagPaths.segment(name), TYPES_FOLDER, types, TagObjectType.UdtType));
            }
        }
    }

    /**
     * Whether a remembered path under {@code folderPath} is no longer what we made it: missing, or (when
     * {@code expected} is given) now a different kind of tag. Paths outside the folder are not this birth's.
     */
    static boolean gone(String path, String folderPath, Map<String, TagObjectType> present, TagObjectType expected) {
        if (!path.startsWith(folderPath + "/")) {
            return false;
        }
        TagObjectType type = present.get(path);
        return type == null || (expected != null && type != expected);
    }

    /** Every path at or under a folder with its type: empty when the folder is gone, null when it can't be read. */
    private Map<String, TagObjectType> presentUnder(String folderPath) {
        try {
            TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
            if (tagProvider == null) {
                return null;
            }
            List<TagConfigurationModel> roots = tagProvider
                .getTagConfigsAsync(List.of(TagPathParser.parse(providerName, folderPath)), true, true)
                .get(15, TimeUnit.SECONDS);
            Map<String, TagObjectType> present = new HashMap<>();
            for (TagConfigurationModel root : roots) {
                walk(folderPath, root, present);
            }
            return present;
        } catch (Exception e) {
            logger.warn("Could not read '{}' in '{}' before a birth; if it was deleted, its tags may come back "
                + "without history until the next birth", folderPath, providerName, e);
            return null;
        }
    }

    private static void walk(String path, TagConfigurationModel model, Map<String, TagObjectType> into) {
        if (model.getType() == null || model.getType() == TagObjectType.Unknown) {
            return; // what a read of a missing path comes back as
        }
        into.put(path, model.getType());
        if (model.getChildren() != null) {
            for (TagConfigurationModel child : model.getChildren()) {
                walk(path + "/" + child.getName(), child, into);
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
            if (previous != null) {
                // Ignition rebuilds every existing instance to the new shape after save() returns. A member the
                // type gained has the same race as a new instance's members: its birth value would land before
                // the member exists and be wiped by the gateway's initial null. So those instances wait again.
                for (Map.Entry<String, String> instance : instances.entrySet()) {
                    if (FLATTENED.equals(instance.getValue())) {
                        continue;
                    }
                    List<String> leaves = memberLeaves(instance.getKey(), instance.getValue(), definedTypes);
                    if (!leaves.isEmpty()) {
                        pendingInstances.add(new PendingInstance(instance.getKey(), leaves));
                    }
                }
            }
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
            // The members of a new instance start as the same Good null as a new tag (see define()), so the
            // instance itself holds their history off until the birth's values are in. endBirth() takes the
            // hold away, and the members go back to what the type says.
            String heldBy = current == null && historize && historian() != null ? birthOf(path) : null;
            List<String> held = heldBy == null ? List.of() : historizedLeaves(path, typeName, definedTypes);
            holdHistory(instance, path, held);
            QualityCode result = save(instance);
            if (result != null && result.isNotGood()) {
                logger.warn("Could not create UDT instance {} of '{}': {}; it stays a folder of tags",
                    path, typeName, result);
                instances.put(path, FLATTENED);
                return false;
            }
            instances.put(path, typeName);
            if (!held.isEmpty()) {
                heldMembers.computeIfAbsent(heldBy, f -> ConcurrentHashMap.newKeySet()).addAll(held);
            }
            // Ignition builds the members from the type after save() returns, so the birth's values for them
            // have the same race as a new atomic tag: awaitDefinitions() waits for these too
            List<String> members = memberLeaves(path, typeName, definedTypes);
            if (!members.isEmpty()) {
                pendingInstances.add(new PendingInstance(path, members));
            }
            return true;
        } catch (Exception e) {
            logger.warn("Could not create UDT instance {} of '{}'; it stays a folder of tags", path, typeName, e);
            instances.put(path, FLATTENED);
            return false;
        }
    }

    /**
     * The atomic tags an instance of a type will have, as full paths, through nested instances. Only types this
     * session declared are known; a nested type we never saw contributes nothing rather than a guess.
     */
    static List<String> memberLeaves(String path, String typeName, Map<String, List<TypeMember>> types) {
        List<String> leaves = new ArrayList<>();
        collectLeaves(path, typeName, types, m -> true, leaves, new HashSet<>());
        return leaves;
    }

    /** The same, only the members the type records history for. */
    static List<String> historizedLeaves(String path, String typeName, Map<String, List<TypeMember>> types) {
        List<String> leaves = new ArrayList<>();
        collectLeaves(path, typeName, types, m -> m.info() != null && m.info().historize(), leaves, new HashSet<>());
        return leaves;
    }

    private static void collectLeaves(String path, String typeName, Map<String, List<TypeMember>> types,
                                      Predicate<TypeMember> wanted, List<String> into, Set<String> seen) {
        List<TypeMember> members = types.get(typeName);
        if (members == null || !seen.add(typeName)) {
            return; // unknown, or a type that contains itself: Ignition would refuse it anyway
        }
        for (TypeMember member : members) {
            String memberPath = path + "/" + member.name();
            if (member.isNested()) {
                collectLeaves(memberPath, member.nestedTypeRef(), types, wanted, into, seen);
            } else if (wanted.test(member)) {
                into.add(memberPath);
            }
        }
        seen.remove(typeName);
    }

    /**
     * An override of history off on each of {@code leaves} (full paths of the instance's atomic members), through
     * nested instances: the shape the Designer saves a member override in. A direct member is named by its full
     * path, as defineType() names a type's members; anything below a nested instance by its name alone. A full
     * path there is appended to the nested instance's own ("…/S1/Drive/Plant/Line1/S1/Drive"), the save fails
     * with Bad_Unsupported and the instance falls back to a folder: seen on 8.3.9 with a nested template.
     */
    private void holdHistory(TagConfiguration instance, String instancePath, List<String> leaves) {
        Map<String, TagConfiguration> nested = new HashMap<>();
        for (String leaf : leaves) {
            TagConfiguration parent = instance;
            String parentPath = instancePath;
            String[] segments = leaf.substring(instancePath.length() + 1).split("/");
            for (int i = 0; i < segments.length - 1; i++) {
                String childPath = parentPath + "/" + segments[i];
                TagConfiguration owner = parent;
                String name = segments[i];
                parent = nested.computeIfAbsent(childPath, p -> {
                    TagConfiguration child = BasicTagConfiguration.createNew(parse(name));
                    child.setType(TagObjectType.UdtInstance);
                    owner.addChild(child);
                    return child;
                });
                parentPath = childPath;
            }
            TagConfiguration member = BasicTagConfiguration.createNew(
                parse(segments.length == 1 ? leaf : segments[segments.length - 1]));
            member.setType(TagObjectType.AtomicTag);
            member.set(TagHistoryProps.HistoryEnabled, false);
            parent.addChild(member);
        }
    }

    private TagPath parse(String path) {
        try {
            return TagPathParser.parse(providerName, path);
        } catch (Exception e) {
            throw new IllegalArgumentException(path, e);
        }
    }

    /**
     * The birth that began with {@link #beginBirth} on this folder has put its last value in, so the new tags and
     * new UDT members it held history back on get it now. Switching history on stores the tag's current value, its
     * birth value, with the birth's timestamp; the Good null the gateway created the tag with never reaches the
     * historian. Waits until the gateway has applied it, so a value that follows the birth is not missed either.
     */
    @Override
    public void endBirth(String folderPath) {
        births.remove(folderPath);
        Set<String> tags = heldTags.remove(folderPath);
        Set<String> members = heldMembers.remove(folderPath);
        List<String> released = new ArrayList<>();
        try {
            if (tags != null) {
                released.addAll(releaseTags(tags));
            }
            if (members != null) {
                released.addAll(releaseMembers(members));
            }
            awaitHistory(released);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Could not switch history on for {} new tags in '{}' under {}; they record from their next "
                + "birth", (tags == null ? 0 : tags.size()) + (members == null ? 0 : members.size()), providerName,
                folderPath, e);
        }
    }

    /** The module's layer again, history included this time. */
    private List<String> releaseTags(Set<String> paths) {
        List<String> released = new ArrayList<>();
        for (String path : paths) {
            Definition definition = defined.get(path);
            if (definition == null || definition.historian() == null) {
                continue; // deleted since, or no historian to record into
            }
            provider.configureTag(path,
                definitionProps(definition.dataType(), definition.info(), definition.historian()));
            released.add(path);
        }
        return released;
    }

    /** Takes the instance's history-off override away from each member, which then records as its type says. */
    private List<String> releaseMembers(Set<String> paths) throws Exception {
        TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
        if (tagProvider == null) {
            return List.of();
        }
        List<TagPath> tagPaths = new ArrayList<>();
        for (String path : paths) {
            tagPaths.add(parse(path));
        }
        List<TagConfiguration> saves = new ArrayList<>();
        List<String> released = new ArrayList<>();
        for (int from = 0; from < tagPaths.size(); from += CONFIG_BATCH) {
            List<TagPath> slice = tagPaths.subList(from, Math.min(tagPaths.size(), from + CONFIG_BATCH));
            for (TagConfigurationModel own : tagProvider.getTagConfigsAsync(slice, false, true)
                .get(30, TimeUnit.SECONDS)) {
                if (own.getPath() == null || !releaseHold(own)) {
                    continue;
                }
                if (own.getType() == null) {
                    own.setType(TagObjectType.AtomicTag);
                }
                saves.add(own);
                released.add(own.getPath().toStringPartial());
            }
        }
        for (int from = 0; from < saves.size(); from += CONFIG_BATCH) {
            List<TagConfiguration> slice = saves.subList(from, Math.min(saves.size(), from + CONFIG_BATCH));
            tagProvider.saveTagConfigsAsync(slice, CollisionPolicy.Overwrite).get(60, TimeUnit.SECONDS);
        }
        return released;
    }

    /** Drops the history-off override defineInstance() put on a member; anything else on it stays. */
    static boolean releaseHold(PropertySet own) {
        if (own.contains(TagHistoryProps.HistoryEnabled)
            && Boolean.FALSE.equals(own.get(TagHistoryProps.HistoryEnabled))) {
            own.remove(TagHistoryProps.HistoryEnabled);
            return true;
        }
        return false;
    }

    private void awaitHistory(List<String> paths) throws Exception {
        if (paths.isEmpty()) {
            return;
        }
        TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
        if (tagProvider == null) {
            return;
        }
        List<TagPath> waiting = new ArrayList<>();
        for (String path : paths) {
            waiting.add(parse(path));
        }
        long deadline = System.currentTimeMillis() + DEFINITION_TIMEOUT_MS;
        while (!waiting.isEmpty()) {
            List<TagPath> still = new ArrayList<>();
            for (int from = 0; from < waiting.size(); from += CONFIG_BATCH) {
                List<TagPath> slice = waiting.subList(from, Math.min(waiting.size(), from + CONFIG_BATCH));
                List<TagConfigurationModel> configs = tagProvider.getTagConfigsAsync(slice, false, false)
                    .get(10, TimeUnit.SECONDS);
                for (int i = 0; i < configs.size() && i < slice.size(); i++) {
                    TagConfigurationModel config = configs.get(i);
                    boolean gone = config.getType() == null || config.getType() == TagObjectType.Unknown;
                    if (!gone && !Boolean.TRUE.equals(config.get(TagHistoryProps.HistoryEnabled))) {
                        still.add(slice.get(i));
                    }
                }
            }
            waiting = still;
            if (waiting.isEmpty()) {
                break;
            }
            if (System.currentTimeMillis() > deadline) {
                logger.warn("Gave up waiting for history to come on for {} new tags in '{}' (first: {}); values "
                    + "before it does are not recorded", waiting.size(), providerName, waiting.get(0));
                break;
            }
            Thread.sleep(20);
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
        Definition definition = new Definition(dataType, info, historize && info.historize() ? historian() : null);
        Definition previous = defined.put(path, definition);
        refresh.add(new Refresh(path, dataType, info));
        if (definition.equals(previous)) {
            return; // a rebirth that says nothing new to the module's layer
        }
        if (previous == null || previous.dataType() != dataType) {
            // created, or retyped: either way the gateway is about to (re)initialize it
            pending.add(new Pending(path, dataType));
        }
        if (info.writable()) {
            allowWrites(path);
        }

        // A tag the gateway is about to create starts life as a null of Good quality (MemoryTagPersistenceState
        // falls through to `new BasicQualifiedValue(null)`), and a historian already attached files that null as
        // a 0, a false or an empty string a few ms before the birth value: a value the edge never sent. So a new
        // tag is created with history off, and endBirth() switches it on once the birth's value is the tag's
        // value; switching history on stores the current value with its own timestamp, so nothing is lost.
        String heldBy = previous == null && definition.historian() != null ? createdBy(path) : null;
        if (heldBy != null) {
            heldTags.computeIfAbsent(heldBy, f -> ConcurrentHashMap.newKeySet()).add(path);
        }
        provider.configureTag(path, definitionProps(dataType, info, heldBy == null ? definition.historian() : null));
    }

    /** The module's whole layer for a tag: configureTag replaces it, so every call carries all of it. */
    private static BoundPropertySet definitionProps(DataType dataType, MetricInfo info, String historian) {
        BoundPropertySet props = new BasicBoundPropertySet();
        props.set(WellKnownTagProps.DataType, dataType);
        applyMetadata(props, info, historian);
        return props;
    }

    /** The open birth that is creating this tag (it was not in the birth's folder when the birth began), or null. */
    private String createdBy(String path) {
        for (Map.Entry<String, Map<String, TagObjectType>> birth : births.entrySet()) {
            if (gone(path, birth.getKey(), birth.getValue(), null)) {
                return birth.getKey();
            }
        }
        return null;
    }

    /** The open birth whose folder holds this path, or null. */
    private String birthOf(String path) {
        for (String folder : births.keySet()) {
            if (path.startsWith(folder + "/")) {
                return folder;
            }
        }
        return null;
    }

    /** Units, range, documentation and the history default — everything a birth says about a metric. */
    private void applyMetadata(BoundPropertySet props, MetricInfo info) {
        applyMetadata(props, info, historize && info.historize() ? historian() : null);
    }

    /** As above, with history defaulted on into {@code historian}, or left alone when that is null. */
    private static void applyMetadata(BoundPropertySet props, MetricInfo info, String historian) {
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
        if (historian != null) {
            historyDefaults(props, historian);
        }
    }

    /**
     * History on, into {@code historian}, storing what the edge reports when it reports it. These go in the
     * module's layer like everything else here, so a person's history setting on a tag (in the Designer, or on a
     * UDT member) still wins over any of them.
     */
    static void historyDefaults(BoundPropertySet props, String historian) {
        props.set(TagHistoryProps.HistoryEnabled, true);
        props.set(TagHistoryProps.HistoryProvider, historian);
        // Ignition's default is at most one sample a second. The edge has already decided what is worth reporting
        // (its RBE deadbands), and a store-and-forward flush arrives far faster than that, so store what we are
        // told. Like everything else here, a user can override it per tag.
        props.set(TagHistoryProps.HistoryTimeDeadband, 0);
        props.set(TagHistoryProps.HistoryTimeDeadbandUnits, TimeUnits.MS);
        // For the same reason, Discrete rather than the default Auto, which is Analog for a float: Analog
        // compression holds each sample back until the next one shows whether it was on a straight line, so a
        // setpoint written once never reached the historian (LevelSP 10 -> 18 left the trend at 10, 2026-10-29).
        // Discrete stores each value as it arrives.
        props.set(TagHistoryProps.HistoricalDeadbandStyle, InterpolationMode.Discrete);
    }

    /**
     * configureTag returns before the gateway has built the tag. A value pushed in that window is applied and then
     * wiped when the tag initializes to null, and a metric that only reports on change (a setpoint, Online) would
     * then sit at null until the next rebirth. So wait until the newest tag in the batch is really there: tags are
     * built in the order they were configured, and births are rare enough to afford it.
     */
    @Override
    public void awaitDefinitions() {
        awaitInstances();
        awaitPending();
        refreshProperties();
    }

    private void awaitPending() {
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

    /**
     * The same wait for the members of new UDT instances, which are built from the type rather than configured one
     * by one. A member that only reports on change (a fault bit, a setpoint) otherwise sat at null after the first
     * birth, with its birth value filed as history behind the gateway's own initial null. One read of every member
     * per poll, not one per member.
     */
    private void awaitInstances() {
        if (pendingInstances.isEmpty()) {
            return;
        }
        List<PendingInstance> batch = List.copyOf(pendingInstances);
        pendingInstances.removeAll(batch);
        List<String> members = batch.stream().flatMap(i -> i.members().stream()).toList();
        long deadline = System.currentTimeMillis() + DEFINITION_TIMEOUT_MS;
        try {
            TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
            List<TagPath> probe = new ArrayList<>();
            for (String member : members) {
                probe.add(TagPathParser.parse(providerName, member));
            }
            while (!allAtomic(tagProvider, probe)) {
                if (System.currentTimeMillis() > deadline) {
                    logger.warn("Gave up waiting for the members of {} new UDT instances in '{}' (first: {}); their "
                        + "first values may be lost until the next change", batch.size(), providerName,
                        batch.get(0).path());
                    break;
                }
                Thread.sleep(20);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Could not confirm new UDT instances in '{}'", providerName, e);
        }
        long now = System.currentTimeMillis();
        for (String member : members) {
            lastTimes.merge(member, now, Math::max);
        }
    }

    /**
     * The birth wins over a person's override for what it carries, and it wins by taking the override away rather
     * than by copying the edge's value over it. A copy would sit in the person's layer, and the Designer would go on
     * marking the property as changed on this tag (its green dot) when nothing about it is the person's any more:
     * 1.3.2 did exactly that. So a plain tag's override is removed and the module's layer, which define() has just
     * set from this birth, shows through. A UDT member inherits from its type instead, so it keeps an override only
     * where the birth really differs from the type, which is all Ignition means by one.
     * <p>
     * One read of the tags' own layers and one save per birth, and nothing is saved when nothing differs. What a
     * birth does not carry (an alarm, a script, security, a history setting) is never touched: the save writes back
     * the layer it read, less what the edge owns.
     */
    private void refreshProperties() {
        if (refresh.isEmpty()) {
            return;
        }
        List<Refresh> batch = List.copyOf(refresh);
        refresh.removeAll(batch);
        try {
            TagProvider tagProvider = context.getTagManager().getTagProvider(providerName);
            if (tagProvider == null) {
                return;
            }
            Map<String, Refresh> wanted = new LinkedHashMap<>();
            for (Refresh r : batch) {
                wanted.put(r.path(), r); // the last word on a path is the birth's
            }
            List<TagPath> paths = new ArrayList<>();
            for (String path : wanted.keySet()) {
                paths.add(TagPathParser.parse(providerName, path));
            }
            List<TagConfiguration> saves = new ArrayList<>();
            List<String> reverted = new ArrayList<>();
            for (int from = 0; from < paths.size(); from += CONFIG_BATCH) {
                List<TagPath> slice = paths.subList(from, Math.min(paths.size(), from + CONFIG_BATCH));
                // localPropsOnly: the person's layer alone, which is what gets written back
                List<TagConfigurationModel> own = tagProvider.getTagConfigsAsync(slice, false, true)
                    .get(30, TimeUnit.SECONDS);
                for (TagConfigurationModel model : own) {
                    if (model.getPath() == null
                        || (model.getType() != null && model.getType() != TagObjectType.AtomicTag)) {
                        continue;
                    }
                    String path = model.getPath().toStringPartial();
                    Refresh r = wanted.get(path);
                    if (r == null) {
                        continue;
                    }
                    Map<Property<?>, Object> birth = carried(r.dataType(), r.info());
                    Map<Property<?>, Object> inherited = r.dataType() != null
                        ? birth // a plain tag: the module's layer says what this birth says
                        : carried(null, typeMemberInfo(path, instances, definedTypes));
                    List<String> replaced = new ArrayList<>();
                    if (reconcile(model, birth, inherited, replaced)) {
                        if (!replaced.isEmpty()) {
                            reverted.add(path + ": " + String.join(", ", replaced));
                        }
                        if (model.getType() == null) {
                            model.setType(TagObjectType.AtomicTag);
                        }
                        saves.add(model);
                    }
                }
            }
            for (int from = 0; from < saves.size(); from += CONFIG_BATCH) {
                List<TagConfiguration> slice = saves.subList(from, Math.min(saves.size(), from + CONFIG_BATCH));
                tagProvider.saveTagConfigsAsync(slice, CollisionPolicy.Overwrite).get(60, TimeUnit.SECONDS);
            }
            // Said per tag, because this is the moment a person finds their edit gone and goes looking for why.
            for (int i = 0; i < Math.min(reverted.size(), REVERTS_LOGGED); i++) {
                logger.info("[{}]{} from the edge's birth. The edge sets these; change them at the edge to keep them.",
                    providerName, reverted.get(i));
            }
            if (reverted.size() > REVERTS_LOGGED) {
                logger.info("... and {} more tags in '{}' had a Designer edit to what the edge sets put back",
                    reverted.size() - REVERTS_LOGGED, providerName);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Could not put the edge's units and documentation back over Designer overrides in '{}'",
                providerName, e);
        }
    }

    /**
     * What a birth sets on a tag: its datatype (a UDT member's comes from the type, so pass null), units, range and
     * documentation. A null is "the birth did not say", never "clear it", so it is left out.
     */
    static Map<Property<?>, Object> carried(DataType dataType, MetricInfo info) {
        Map<Property<?>, Object> carried = new LinkedHashMap<>();
        if (dataType != null) {
            carried.put(WellKnownTagProps.DataType, dataType);
        }
        if (info == null) {
            return carried;
        }
        if (info.engUnit() != null) {
            carried.put(WellKnownTagProps.EngUnit, info.engUnit());
        }
        if (info.documentation() != null) {
            carried.put(WellKnownTagProps.Documentation, info.documentation());
        }
        if (info.engLow() != null) {
            carried.put(WellKnownTagProps.EngLow, info.engLow());
        }
        if (info.engHigh() != null) {
            carried.put(WellKnownTagProps.EngHigh, info.engHigh());
        }
        return carried;
    }

    /** per birth; past this the log says how many more rather than listing a mass edit line by line */
    private static final int REVERTS_LOGGED = 20;

    /** the tag editor's own names for what a birth carries, so the log reads like the screen the person was on */
    private static final Map<Property<?>, String> LABELS = Map.of(
        WellKnownTagProps.DataType, "Data Type",
        WellKnownTagProps.EngUnit, "Engineering Units",
        WellKnownTagProps.EngLow, "Engineering Low Limit",
        WellKnownTagProps.EngHigh, "Engineering High Limit",
        WellKnownTagProps.Documentation, "Documentation");

    /**
     * Brings a tag's own layer in line with a birth: an override of something the birth carries is dropped where
     * the tag would inherit the birth's value anyway, and set where it would not. Returns whether {@code own}
     * changed. Each edit of a person's that the birth undoes is described into {@code replaced}; housekeeping is
     * not (a member's first override from its birth, or a copy of the edge's own value 1.3.2 left behind).
     */
    static boolean reconcile(PropertySet own, Map<Property<?>, Object> birth, Map<Property<?>, Object> inherited,
                             List<String> replaced) {
        boolean changed = false;
        for (Map.Entry<Property<?>, Object> carried : birth.entrySet()) {
            Property<?> prop = carried.getKey();
            Object value = carried.getValue();
            if (own.contains(prop) && !value.equals(own.get(prop))) {
                replaced.add(LABELS.getOrDefault(prop, prop.getName()) + " '" + own.get(prop) + "' -> '" + value + "'");
            }
            if (value.equals(inherited.get(prop))) {
                if (own.contains(prop)) {
                    own.remove(prop);
                    changed = true;
                }
            } else if (!own.contains(prop) || !value.equals(own.get(prop))) {
                set(own, prop, value);
                changed = true;
            }
        }
        return changed;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void set(PropertySet own, Property prop, Object value) {
        own.set(prop, value);
    }

    /**
     * What a UDT member's type says about it, found by walking from the instance it belongs to through any nested
     * types; null when the member is not one this module built.
     */
    static MetricInfo typeMemberInfo(String memberPath, Map<String, String> instances,
                                     Map<String, List<TypeMember>> types) {
        for (Map.Entry<String, String> instance : instances.entrySet()) {
            String prefix = instance.getKey() + "/";
            if (FLATTENED.equals(instance.getValue()) || !memberPath.startsWith(prefix)) {
                continue;
            }
            String typeName = instance.getValue();
            String[] segments = memberPath.substring(prefix.length()).split("/");
            for (int i = 0; i < segments.length; i++) {
                TypeMember member = memberNamed(types.get(typeName), segments[i]);
                if (member == null) {
                    return null;
                }
                if (i == segments.length - 1) {
                    return member.isNested() ? null : member.info();
                }
                if (!member.isNested()) {
                    return null;
                }
                typeName = member.nestedTypeRef();
            }
        }
        return null;
    }

    private static TypeMember memberNamed(List<TypeMember> members, String name) {
        if (members != null) {
            for (TypeMember member : members) {
                if (member.name().equals(name)) {
                    return member;
                }
            }
        }
        return null;
    }

    private static boolean allAtomic(TagProvider tagProvider, List<TagPath> probe) throws Exception {
        List<TagConfigurationModel> configs = tagProvider.getTagConfigsAsync(probe, false, false)
            .get(10, TimeUnit.SECONDS);
        return configs.size() == probe.size()
            && configs.stream().allMatch(c -> c.getType() == TagObjectType.AtomicTag);
    }

    private static boolean ready(TagProvider tagProvider, List<TagPath> probe, DataType dataType) throws Exception {
        List<TagConfigurationModel> configs = tagProvider.getTagConfigsAsync(probe, false, false)
            .get(10, TimeUnit.SECONDS);
        return configs.size() == 1 && configs.get(0).getType() == TagObjectType.AtomicTag
            && configs.get(0).get(WellKnownTagProps.DataType) == dataType;
    }

    /**
     * The historian new tags are pointed at, or null when the gateway has none. Null is worth shouting about:
     * a tag with history switched off looks exactly like a tag that is recording, and the whole point of this
     * module is that you should not have to configure anything for your data to be kept.
     */
    /** Whether this sink was told to historize what arrives — the default, and the thing a user can turn off. */
    public boolean historize() {
        return historize;
    }

    public String historian() {
        if (historyProvider != null) {
            return historyProvider;
        }
        List<String> historians = context.getTagHistoryManager().getTagHistoryProviders();
        if (historians.isEmpty()) {
            if (historize && noHistorianWarned.compareAndSet(false, true)) {
                logger.warn("No tag historian is configured on this gateway, so tags in '{}' are being created "
                    + "with history OFF and nothing is being recorded. Add a historian (the Historian module, or "
                    + "any tag history provider) and the next birth will switch history on by itself.",
                    providerName);
            }
            return null;
        }
        // recovered: say so, and let the next birth pick it up
        if (noHistorianWarned.compareAndSet(true, false)) {
            logger.info("Tag historian '{}' is available; tags in '{}' will record from their next birth.",
                historians.get(0), providerName);
        }
        return historians.get(0);
    }

    @Override
    public void refresh(String path, MetricInfo info) {
        refresh.add(new Refresh(path, null, info));
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
