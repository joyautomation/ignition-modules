package com.joyautomation.ignition.mantle.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
import com.joyautomation.ignition.mantle.sparkplug.TagSink.MetricInfo;
import com.joyautomation.ignition.mantle.sparkplug.TagSink.TypeMember;
import org.eclipse.tahu.message.model.MetricDataType;
import org.junit.jupiter.api.Test;

/**
 * The sink itself needs a gateway, so this covers the decision it makes before every birth: which remembered
 * definitions no longer match the provider. Getting it wrong one way is a redundant configureTag; the other way
 * is a deleted node coming back with history off, which is what the im12 take caught on 2026-10-09.
 */
class ManagedTagSinkTest {
    private static final String NODE = "Plant/Line1";

    @Test
    void aTagDeletedWithItsFolderIsForgotten() {
        Map<String, TagObjectType> deleted = Map.of();
        assertTrue(ManagedTagSink.gone("Plant/Line1/LevelFt", NODE, deleted, TagObjectType.AtomicTag));
    }

    @Test
    void aTagStillThereIsKept() {
        Map<String, TagObjectType> present = Map.of(NODE, TagObjectType.Folder,
            "Plant/Line1/LevelFt", TagObjectType.AtomicTag);
        assertFalse(ManagedTagSink.gone("Plant/Line1/LevelFt", NODE, present, TagObjectType.AtomicTag));
    }

    @Test
    void anInstanceThatIsNowAFolderIsForgotten() {
        Map<String, TagObjectType> present = Map.of("Plant/Line1/P101", TagObjectType.Folder);
        assertTrue(ManagedTagSink.gone("Plant/Line1/P101", NODE, present, TagObjectType.UdtInstance));
        // a flattened instance is a folder on purpose, and only has to exist
        assertFalse(ManagedTagSink.gone("Plant/Line1/P101", NODE, present, null));
    }

    @Test
    void anotherNodesTagsAreNotThisBirthsBusiness() {
        assertFalse(ManagedTagSink.gone("Plant/Line2/LevelFt", NODE, Map.of(), TagObjectType.AtomicTag));
        assertFalse(ManagedTagSink.gone("Plant/Line10/LevelFt", "Plant/Line1", Map.of(), TagObjectType.AtomicTag));
    }

    // ── the members a new UDT instance has to wait for ──────────────────────────

    private static TypeMember atomic(String name) {
        return new TypeMember(name, MetricDataType.Boolean, null, MetricInfo.DEFAULT);
    }

    @Test
    void aNewInstanceWaitsForEveryAtomicMember() {
        Map<String, List<TypeMember>> types = Map.of("Motor", List.of(atomic("Fault"), atomic("Running")));
        assertEquals(List.of("Plant/Line1/P101/Fault", "Plant/Line1/P101/Running"),
            ManagedTagSink.memberLeaves("Plant/Line1/P101", "Motor", types));
    }

    @Test
    void aNestedInstancesMembersAreItsParentsToo() {
        Map<String, List<TypeMember>> types = Map.of(
            "Skid", List.of(atomic("Enable"), new TypeMember("Pump", MetricDataType.Template, "Motor", MetricInfo.DEFAULT)),
            "Motor", List.of(atomic("Fault")));
        assertEquals(List.of("S1/Enable", "S1/Pump/Fault"), ManagedTagSink.memberLeaves("S1", "Skid", types));
    }

    @Test
    void aTypeWeNeverSawOrOneThatContainsItselfAddsNothing() {
        assertEquals(List.of(), ManagedTagSink.memberLeaves("P101", "Motor", Map.of()));
        Map<String, List<TypeMember>> loop = Map.of(
            "Loop", List.of(atomic("A"), new TypeMember("Again", MetricDataType.Template, "Loop", MetricInfo.DEFAULT)));
        assertEquals(List.of("X/A"), ManagedTagSink.memberLeaves("X", "Loop", loop));
    }
}
