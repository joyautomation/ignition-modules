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
     * Called for every metric in a birth. Must be idempotent: a rebirth of a tag that already exists must not
     * disturb anything a user has customized on it.
     */
    void define(String path, MetricDataType type, MetricInfo info);

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
