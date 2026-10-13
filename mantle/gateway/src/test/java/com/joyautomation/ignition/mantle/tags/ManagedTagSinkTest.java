package com.joyautomation.ignition.mantle.tags;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import com.inductiveautomation.ignition.common.tags.config.types.TagObjectType;
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
}
