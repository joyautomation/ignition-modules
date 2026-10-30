package com.joyautomation.ignition.mantle.sparkplug;

import java.util.Date;
import java.util.List;

import org.eclipse.tahu.message.model.MetricDataType;

/**
 * Where the host application puts what it learns. The gateway implementation is backed by a managed tag provider;
 * tests use an in-memory one.
 */
public interface TagSink {

    /**
     * Called once at the start of every birth, before any {@link #define}, with the node's or device's folder.
     * A sink that skips redefining what it has already defined must check here that those tags still exist:
     * a person can delete a folder at any time, and the birth after that has to build it again in full.
     */
    default void beginBirth(String folderPath) {
    }

    /**
     * Called for every metric in a birth. Must be idempotent: a rebirth of a tag that already exists must not
     * disturb anything a user has customized on it that the birth does not carry. What it does carry (datatype,
     * units, range, documentation) is the edge's to set, and a rebirth puts it back over a user's override.
     */
    void define(String path, MetricDataType type, MetricInfo info);

    /**
     * A member of a UDT instance, with whatever the birth said about it beyond its type. Ignition built the tag
     * from the type, so there is nothing to define, but the birth's units and documentation still beat a user's
     * override, as for {@link #define}.
     */
    default void refresh(String path, MetricInfo info) {
    }

    /**
     * A Sparkplug template definition, as an Ignition UDT type. Called before any instance that refers to it,
     * and nested types before the types that use them. Idempotent: a rebirth redeclares every type.
     *
     * @return true when the type is usable, so instances of it can be created. False sends the caller back to
     *         flattening the instance into a folder, which always works.
     */
    default boolean defineType(String typeName, List<TypeMember> members) {
        return false;
    }

    /**
     * A template instance, as an instance of a UDT type that {@link #defineType} has already declared.
     *
     * @return false when the path cannot become a UDT instance — most often because it is already a folder of
     *         tags from an older version of this module — in which case the caller flattens it as before.
     */
    default boolean defineInstance(String path, String typeName) {
        return false;
    }

    /**
     * One member of a UDT type: either a scalar (with a datatype) or a nested instance of another type
     * (with {@code nestedTypeRef} set).
     */
    record TypeMember(String name, MetricDataType type, String nestedTypeRef, MetricInfo info) {
        public boolean isNested() {
            return nestedTypeRef != null;
        }
    }

    /**
     * A member of a UDT instance. Ignition builds the tag itself from the type, so there is nothing to define,
     * but a write to it still has to become a command to the edge.
     */
    default void allowWrites(String path) {
    }

    /**
     * Called once per birth, after every {@link #define} and before the first {@link #update}: return when the tags
     * just defined can take a value. A birth is shape first, then values, because a tag system may create tags
     * asynchronously, and a value that arrives before its tag is ready can be wiped when the tag initializes.
     */
    default void awaitDefinitions() {
    }

    /**
     * Called once at the end of every birth that {@link #beginBirth} opened, with the same folder, after the
     * birth's last value (its {@code _meta/Online} included), and also when the birth failed part way. A sink
     * that holds anything back until a new tag has its first value lets go of it here.
     */
    default void endBirth(String folderPath) {
    }

    void update(String path, Object value, Date timestamp, boolean historical);

    /** The metric was reported with is_null = true. */
    void updateNull(String path, Date timestamp);

    /**
     * Everything under {@code folderPath} has lost its source (NDEATH, DDEATH, or broker disconnect). Last values
     * are kept; only quality changes.
     */
    void markStale(String folderPath, Date timestamp);

    /**
     * What a birth says about a metric beyond its type. String and Double fields are nullable.
     *
     * @param historize false for metrics the edge flagged is_transient, which default to history off
     * @param writable false for the module's own status tags; Sparkplug metrics are writable and the edge decides
     *                 whether to honour the command
     */
    record MetricInfo(String engUnit, String documentation, Double engLow, Double engHigh, boolean historize,
                      boolean writable) {
        public static final MetricInfo DEFAULT = new MetricInfo(null, null, null, null, true, true);
    }
}
