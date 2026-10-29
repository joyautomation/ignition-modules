package com.joyautomation.ignition.mantle.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.inductiveautomation.ignition.common.config.BasicBoundPropertySet;
import com.inductiveautomation.ignition.common.config.Property;
import com.inductiveautomation.ignition.common.sqltags.model.types.DataType;
import com.inductiveautomation.ignition.common.tags.config.properties.WellKnownTagProps;
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

    // ── what a rebirth puts back over a person's override ──────────────────────

    private static MetricInfo birth(String unit, String doc) {
        return new MetricInfo(unit, doc, null, null, true, true);
    }

    // A plain tag inherits from the module's layer, which the birth has just set, so "inherited" is the birth.

    @Test
    void aRetypedUnitIsTakenAwayNotCopiedOver() {
        BasicBoundPropertySet own = new BasicBoundPropertySet();
        own.set(WellKnownTagProps.EngUnit, "feet");
        Map<Property<?>, Object> birth = ManagedTagSink.carried(DataType.Float8, birth("ft", "Level"));
        assertTrue(ManagedTagSink.reconcile(own, birth, birth, new ArrayList<>()));
        // gone from the person's layer, so the Designer stops marking it as changed on this tag
        assertFalse(own.contains(WellKnownTagProps.EngUnit));
    }

    @Test
    void aRevertedEditIsDescribedInTheEditorsWords() {
        BasicBoundPropertySet own = new BasicBoundPropertySet();
        own.set(WellKnownTagProps.EngUnit, "feet");
        own.set(WellKnownTagProps.Documentation, "Level"); // a 1.3.2 copy of the edge's own value: housekeeping
        Map<Property<?>, Object> birth = ManagedTagSink.carried(DataType.Float8, birth("ft", "Level"));
        List<String> replaced = new ArrayList<>();
        assertTrue(ManagedTagSink.reconcile(own, birth, birth, replaced));
        assertEquals(List.of("Engineering Units 'feet' -> 'ft'"), replaced);
    }

    @Test
    void aMembersFirstOverrideFromItsBirthIsNotCalledARevert() {
        Map<Property<?>, Object> birth = ManagedTagSink.carried(null, birth("rpm", null));
        List<String> replaced = new ArrayList<>();
        assertTrue(ManagedTagSink.reconcile(new BasicBoundPropertySet(), birth, Map.of(), replaced));
        assertTrue(replaced.isEmpty());
    }

    @Test
    void aTagNobodyEditedIsLeftAlone() {
        Map<Property<?>, Object> birth = ManagedTagSink.carried(DataType.Float8, birth("ft", "Level"));
        assertFalse(ManagedTagSink.reconcile(new BasicBoundPropertySet(), birth, birth, new ArrayList<>()));
    }

    @Test
    void whatTheBirthDoesNotCarryIsNotTouched() {
        BasicBoundPropertySet own = new BasicBoundPropertySet();
        own.set(WellKnownTagProps.EngUnit, "feet");
        own.set(WellKnownTagProps.Tooltip, "mine");
        // this birth says nothing about units, and nothing about tooltips ever
        Map<Property<?>, Object> birth = ManagedTagSink.carried(DataType.Float8, birth(null, "Level"));
        assertFalse(ManagedTagSink.reconcile(own, birth, birth, new ArrayList<>()));
        assertEquals("feet", own.get(WellKnownTagProps.EngUnit));
        assertEquals("mine", own.get(WellKnownTagProps.Tooltip));
    }

    @Test
    void aRetypedTagGetsItsDatatypeBack() {
        BasicBoundPropertySet own = new BasicBoundPropertySet();
        own.set(WellKnownTagProps.DataType, DataType.Int4);
        Map<Property<?>, Object> birth = ManagedTagSink.carried(DataType.Float8, birth(null, null));
        assertTrue(ManagedTagSink.reconcile(own, birth, birth, new ArrayList<>()));
        assertFalse(own.contains(WellKnownTagProps.DataType));
    }

    // A UDT member inherits from its type, which may say something different from the instance's birth.

    @Test
    void aMemberKeepsAnOverrideOnlyWhereItsBirthDiffersFromItsType() {
        BasicBoundPropertySet own = new BasicBoundPropertySet();
        own.set(WellKnownTagProps.EngUnit, "rpm by hand");
        own.set(WellKnownTagProps.Documentation, "by hand");
        Map<Property<?>, Object> birth = ManagedTagSink.carried(null, birth("rpm", "Shaft speed"));
        Map<Property<?>, Object> type = ManagedTagSink.carried(null, birth("rpm", null));
        assertTrue(ManagedTagSink.reconcile(own, birth, type, new ArrayList<>()));
        assertFalse(own.contains(WellKnownTagProps.EngUnit), "the type already says rpm: no override");
        assertEquals("Shaft speed", own.get(WellKnownTagProps.Documentation), "the type says nothing: override");
    }

    @Test
    void aMemberThatAlreadyOverridesWithTheBirthsValueIsLeftAlone() {
        BasicBoundPropertySet own = new BasicBoundPropertySet();
        own.set(WellKnownTagProps.Documentation, "Shaft speed");
        Map<Property<?>, Object> birth = ManagedTagSink.carried(null, birth(null, "Shaft speed"));
        assertFalse(ManagedTagSink.reconcile(own, birth, Map.of(), new ArrayList<>()));
    }

    @Test
    void aMembersTypeIsFoundThroughNestedTypes() {
        MetricInfo torque = new MetricInfo("Nm", null, null, null, true, true);
        Map<String, List<TypeMember>> types = Map.of(
            "Motor", List.of(new TypeMember("Drive", null, "Drive", MetricInfo.DEFAULT)),
            "Drive", List.of(new TypeMember("Torque", MetricDataType.Float, null, torque)));
        Map<String, String> instances = Map.of("Plant/Line1/P101", "Motor");
        assertEquals(torque, ManagedTagSink.typeMemberInfo("Plant/Line1/P101/Drive/Torque", instances, types));
        assertEquals(null, ManagedTagSink.typeMemberInfo("Plant/Line1/P101/Drive/Nope", instances, types));
        assertEquals(null, ManagedTagSink.typeMemberInfo("Plant/Line1/LevelFt", instances, types));
    }
}
