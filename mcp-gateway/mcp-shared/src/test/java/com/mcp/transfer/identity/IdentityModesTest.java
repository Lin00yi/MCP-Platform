package com.mcp.transfer.identity;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class IdentityModesTest {

    private IdentityProperties enabled(String mode) {
        IdentityProperties properties = new IdentityProperties();
        properties.setEnabled(true);
        properties.setMode(mode);
        return properties;
    }

    @Test
    void disabledAlwaysOff() {
        IdentityProperties properties = enabled("REQUIRED");
        properties.setEnabled(false);
        assertEquals(IdentityMode.OFF, IdentityModes.effective(properties, "REQUIRED"));
    }

    @Test
    void serverOverrideWins() {
        assertEquals(IdentityMode.OFF, IdentityModes.effective(enabled("REQUIRED"), "OFF"));
        assertEquals(IdentityMode.OPTIONAL, IdentityModes.effective(enabled("REQUIRED"), "OPTIONAL"));
        assertEquals(IdentityMode.REQUIRED, IdentityModes.effective(enabled("OFF"), "REQUIRED"));
    }

    @Test
    void inheritFallsBackToGlobal() {
        assertEquals(IdentityMode.REQUIRED, IdentityModes.effective(enabled("REQUIRED"), "INHERIT"));
        assertEquals(IdentityMode.OPTIONAL, IdentityModes.effective(enabled("OPTIONAL"), null));
    }

    @Test
    void blankGlobalDefaultsToOptional() {
        assertEquals(IdentityMode.OPTIONAL, IdentityModes.effective(enabled(null), "INHERIT"));
    }

    @Test
    void normalizeAndValidate() {
        assertEquals("INHERIT", IdentityModes.normalizeServerMode(null));
        assertEquals("INHERIT", IdentityModes.normalizeServerMode("bogus"));
        assertEquals("REQUIRED", IdentityModes.normalizeServerMode("required"));
        assertTrue(IdentityModes.isValidServerMode("inherit"));
        assertFalse(IdentityModes.isValidServerMode("nope"));
        assertTrue(IdentityMode.REQUIRED.enabled());
        assertFalse(IdentityMode.OFF.enabled());
    }
}
