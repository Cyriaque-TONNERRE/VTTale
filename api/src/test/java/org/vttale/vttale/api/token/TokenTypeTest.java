package org.vttale.vttale.api.token;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers CoreTokenType plus the default methods TokenType gives every third-party type.
 */
class TokenTypeTest {

    /** A minimal third-party type, exactly as an add-on author would write one. */
    private record CustomType(String id, String displayName, String shortName)
            implements TokenType {
        @Override public String getId() { return id; }
        @Override public String getDisplayName() { return displayName; }
        @Override public String getShortName() { return shortName; }
    }

    @Test
    @DisplayName("fromId resolves known ids and returns null otherwise")
    void fromId() {
        assertSame(CoreTokenType.NPC, CoreTokenType.fromId("core:npc"));
        assertSame(CoreTokenType.MARKER, CoreTokenType.fromId("core:marker"));
        assertNull(CoreTokenType.fromId("core:unknown"));
        assertNull(CoreTokenType.fromId(null));
        assertNull(CoreTokenType.fromId("CORE:NPC"), "fromId is case sensitive");
    }

    @Test
    @DisplayName("fromShortName is case insensitive")
    void fromShortName() {
        assertSame(CoreTokenType.PLAYER_CHARACTER, CoreTokenType.fromShortName("PC"));
        assertSame(CoreTokenType.PLAYER_CHARACTER, CoreTokenType.fromShortName("pc"));
        assertNull(CoreTokenType.fromShortName("nope"));
        assertNull(CoreTokenType.fromShortName(null));
    }

    @Test
    @DisplayName("namespace and name are split on the first colon")
    void namespaceAndName() {
        assertEquals("core", CoreTokenType.MONSTER.getNamespace());
        assertEquals("monster", CoreTokenType.MONSTER.getName());

        TokenType custom = new CustomType("mymod:dragon", "Dragon", "DRG");
        assertEquals("mymod", custom.getNamespace());
        assertEquals("dragon", custom.getName());
    }

    @Test
    @DisplayName("an id without a namespace silently reports namespace \"core\"")
    void unqualifiedIdClaimsCoreNamespace() {
        // Documents current behaviour, which is a trap for add-on authors: an unqualified id
        // is attributed to the core namespace instead of being rejected.
        TokenType custom = new CustomType("dragon", "Dragon", "DRG");
        assertEquals("core", custom.getNamespace());
        assertEquals("dragon", custom.getName());
    }

    @Test
    @DisplayName("matches compares ids, not identity")
    void matches() {
        TokenType sameIdDifferentInstance = new CustomType("core:npc", "Other label", "X");
        assertTrue(CoreTokenType.NPC.matches(sameIdDifferentInstance));
        assertTrue(CoreTokenType.NPC.matches("core:npc"));
        assertFalse(CoreTokenType.NPC.matches(CoreTokenType.MONSTER));
        assertFalse(CoreTokenType.NPC.matches((String) null));
        assertFalse(CoreTokenType.NPC.matches((TokenType) null));
    }
}
